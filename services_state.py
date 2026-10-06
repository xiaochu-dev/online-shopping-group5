# -*- coding: utf-8 -*-
"""
services_state.py —— 商品状态机 + 队列/递补逻辑（公共层）
====================================================
# 公共数据层与状态机骨架 —— 架构设计：胡成溯（组长）

# 队列与冻结模块 —— 周到（PM）：FR-04 / FR-06（Backlog D/F）；骨架由组长提供，组员完善署名

商品状态机（3 态）：
    on_sale（在售）--进入交易/手动冻结--> frozen（已冻结）
    frozen --标记失败/手动解冻--> on_sale
    frozen --标记成功--> delisted（已下架，终态，入历史）

意向状态机（5 态）：
    pending（排队中）→ trading（交易中）→ success / failed
    pending → cancelled（凭口令码撤销）
    标记成功时：未成交的 pending 意向一律 → failed

队列规则：
    - 意向按提交时间正序（created_at 精确到毫秒，自增 id 兜底），严格先到先得
    - 仅队首（当前 trading 中的意向）可进入线下交易
    - 队首交易失败 → 后一位自动递补为 trading、商品回到 frozen
    - 队列为空 → 商品留在 on_sale
"""

import sqlite3
from datetime import datetime

from db import get_conn


# ---------------------------------------------------------------------------
# 时间工具
# ---------------------------------------------------------------------------

def now_ms() -> str:
    """当前时间文本，精确到毫秒（可按字典序排序：YYYY-MM-DD HH:MM:SS.mmm）。"""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


# ---------------------------------------------------------------------------
# 口令码生成
# ---------------------------------------------------------------------------

def generate_token_code() -> str:
    """生成唯一口令码：8 位十六进制（secrets.token_hex(4)）。

    secrets 模块基于操作系统安全随机源，保证不可枚举（NFR-05）。
    入库前循环检查唯一性，杜绝撞码。
    """
    while True:
        code = __import__("secrets").token_hex(4)  # 8 位十六进制
        conn = get_conn()
        try:
            row = conn.execute(
                "SELECT id FROM intent WHERE token_code = ?", (code,)
            ).fetchone()
            if row is None:
                return code
        finally:
            conn.close()


# ---------------------------------------------------------------------------
# 商品查询
# ---------------------------------------------------------------------------

def get_current_product(conn: sqlite3.Connection) -> sqlite3.Row | None:
    """当前商品：同一时间仅允许一件在售/已冻结商品（FR-02-1）。"""
    return conn.execute(
        "SELECT * FROM product WHERE status IN ('on_sale', 'frozen') "
        "ORDER BY id DESC LIMIT 1"
    ).fetchone()


def get_product_by_id(conn: sqlite3.Connection, product_id: int) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM product WHERE id = ?", (product_id,)
    ).fetchone()


# ---------------------------------------------------------------------------
# 队列查询与排序
# ---------------------------------------------------------------------------

def list_queue(conn: sqlite3.Connection, product_id: int, include_trading: bool = False) -> list[sqlite3.Row]:
    """某商品的有效意向队列：pending（可选含 trading），按提交时间正序。

    排序键：created_at（毫秒精度文本，字典序=时间序），id 自增兜底（NFR-03 并发入库顺序）。
    """
    statuses = "('trading', 'pending')" if include_trading else "('pending')"
    return conn.execute(
        f"SELECT * FROM intent WHERE product_id = ? AND status IN {statuses} "
        "ORDER BY created_at ASC, id ASC",
        (product_id,),
    ).fetchall()


def get_head_intent(conn: sqlite3.Connection, product_id: int) -> sqlite3.Row | None:
    """当前队首：trading 状态的意向（同一时间只有队首进入交易，FR-04-3）。"""
    return conn.execute(
        "SELECT * FROM intent WHERE product_id = ? AND status = 'trading' "
        "ORDER BY created_at ASC, id ASC LIMIT 1",
        (product_id,),
    ).fetchone()


def has_trading_intent(conn: sqlite3.Connection, product_id: int) -> bool:
    return get_head_intent(conn, product_id) is not None


# ---------------------------------------------------------------------------
# 意向提交 / 撤销
# ---------------------------------------------------------------------------

def create_intent(conn: sqlite3.Connection, product_id: int, buyer_name: str, phone: str) -> str:
    """提交意向：生成口令码入库，返回口令码。不做号码去重（FR-03-6/7）。"""
    token = generate_token_code()
    conn.execute(
        "INSERT INTO intent (product_id, buyer_name, phone, token_code, status, created_at) "
        "VALUES (?, ?, ?, ?, 'pending', ?)",
        (product_id, buyer_name, phone, token, now_ms()),
    )
    return token


def cancel_intent(conn: sqlite3.Connection, token_code: str) -> sqlite3.Row | None:
    """凭口令码撤销 pending 意向（D-3 / FR-04-4~6）。

    撤销成功返回该意向；仅 pending 可撤，其余状态返回 None。
    撤销后队列位次自动前移（list_queue 按 created_at 排序，天然前移）。
    """
    row = conn.execute(
        "SELECT * FROM intent WHERE token_code = ? AND status = 'pending'",
        (token_code,),
    ).fetchone()
    if row is None:
        return None
    conn.execute(
        "UPDATE intent SET status = 'cancelled' WHERE id = ?", (row["id"],)
    )
    return row


# ---------------------------------------------------------------------------
# 冻结 / 解冻（自动冻结与手动冻结是同一个状态，FR-06-3）
# ---------------------------------------------------------------------------

def freeze_product(conn: sqlite3.Connection, product_id: int) -> None:
    """冻结商品：on_sale → frozen（手动冻结路径）。"""
    conn.execute(
        "UPDATE product SET status = 'frozen' WHERE id = ? AND status = 'on_sale'",
        (product_id,),
    )


def unfreeze_product(conn: sqlite3.Connection, product_id: int) -> None:
    """解冻商品：frozen → on_sale（仅当没有交易进行中；有 trading 意向时不允许解冻，
    否则会破坏「仅队首进入交易」的队列语义）。"""
    if has_trading_intent(conn, product_id):
        raise ValueError("交易进行中，无法解冻；请先标记交易结果")
    conn.execute(
        "UPDATE product SET status = 'on_sale' WHERE id = ? AND status = 'frozen'",
        (product_id,),
    )


# ---------------------------------------------------------------------------
# 进入交易（E-2 / FR-06-1 自动冻结）
# ---------------------------------------------------------------------------

def enter_trading(conn: sqlite3.Connection, intent_id: int) -> tuple[bool, str]:
    """队首意向进入交易：意向 pending→trading，商品 on_sale→frozen。

    返回 (是否成功, 消息)。仅 pending 且位于队首的意向可进入交易。
    """
    intent = conn.execute(
        "SELECT * FROM intent WHERE id = ?", (intent_id,)
    ).fetchone()
    if intent is None:
        return False, "意向不存在"
    if intent["status"] != "pending":
        return False, "该意向不在排队中，无法进入交易"
    product = get_product_by_id(conn, intent["product_id"])
    if product is None or product["status"] != "on_sale":
        return False, "商品当前状态不允许进入交易"
    # 队首校验：按队列顺序，第一个 pending 之外的交易位必须为空
    head = get_head_intent(conn, product["id"])
    if head is not None:
        return False, "已有买家在交易中，仅队首可进入交易"
    queue = list_queue(conn, product["id"])
    if not queue or queue[0]["id"] != intent_id:
        return False, "仅队首意向可进入交易"
    # 自动冻结：在售 → 已冻结；该意向 → 交易中
    conn.execute("UPDATE product SET status = 'frozen' WHERE id = ?", (product["id"],))
    conn.execute("UPDATE intent SET status = 'trading' WHERE id = ?", (intent_id,))
    return True, "已进入交易，商品已自动冻结"


# ---------------------------------------------------------------------------
# 标记交易结果（FR-07）
# ---------------------------------------------------------------------------

def mark_result(conn: sqlite3.Connection, product_id: int, success: bool) -> tuple[bool, str]:
    """卖家标记交易结果（唯一入口在卖家后台）。

    成功（FR-07-5）：
        商品 → delisted（终态，入历史），写 traded_at / final_result
        队首意向 → success；其余 pending 意向 → failed（交易结束商品没了）

    失败（FR-07-6 / FR-07-7）：
        队首意向 → failed
        队列仍有 pending → 下一位 → trading，商品保持 frozen（自动递补）
        队列为空 → 商品恢复 on_sale
    """
    product = get_product_by_id(conn, product_id)
    if product is None:
        return False, "商品不存在"
    head = get_head_intent(conn, product_id)
    if head is None:
        return False, "当前没有进行中的交易，无法标记结果"
    if product["status"] != "frozen":
        return False, "商品状态异常（应为已冻结）"

    if success:
        # 商品下架入历史
        conn.execute(
            "UPDATE product SET status = 'delisted', traded_at = ?, final_result = '成功' "
            "WHERE id = ?",
            (now_ms(), product_id),
        )
        conn.execute("UPDATE intent SET status = 'success' WHERE id = ?", (head["id"],))
        # 其余排队意向全部转失败
        conn.execute(
            "UPDATE intent SET status = 'failed' "
            "WHERE product_id = ? AND status = 'pending'",
            (product_id,),
        )
        return True, "交易成功：商品已下架进入历史"
    else:
        # 队首标记失败
        conn.execute("UPDATE intent SET status = 'failed' WHERE id = ?", (head["id"],))
        # 自动递补：队列下一位（pending 中最早提交者）→ trading，商品再次冻结
        queue = list_queue(conn, product_id)
        if queue:
            conn.execute(
                "UPDATE intent SET status = 'trading' WHERE id = ?", (queue[0]["id"],)
            )
            conn.execute("UPDATE product SET status = 'frozen' WHERE id = ?", (product_id,))
            return True, "交易失败：商品保持冻结，下一位已自动递补"
        # 队列为空：商品恢复在售
        conn.execute("UPDATE product SET status = 'on_sale' WHERE id = ?", (product_id,))
        return True, "交易失败：商品已恢复在售，队列为空"

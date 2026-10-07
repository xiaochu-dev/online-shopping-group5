# -*- coding: utf-8 -*-
"""
test_d_module.py —— 队列与冻结模块边界自测（周到 · PM 收口任务）
====================================================
覆盖 FR-04（意向队列与排队规则）与 FR-06（商品冻结与解冻）的全部验收标准：

  D 组（队列与撤销）
    D-1  后台意向人列表按提交时间正序
    D-2  非队首进入交易被拒，后台仅队首有可用入口
    D-3  正确口令码撤销成功 / 错误口令码被拒 / 撤销后从队列消失
    D-4  撤销第 2 位后，原第 3 位顺位前移为第 2 位
    D-5  队首交易失败后，原第 2 位自动递补为队首并可交易，商品再次冻结

  F 组（冻结解冻）
    F-1  卖家选定队首进入交易，商品自动 在售→已冻结
    F-2  手动冻结与自动冻结是同一个状态
    F-3  冻结期买家页仍显示商品但提示「商品交易中」
    F-4  冻结期提交新意向接口拒绝，且数据库无新意向
    F-5  手动解冻恢复在售可收新意向；交易失败自动递补，队列空后恢复在售

用例彼此独立：每个用例通过 fresh_env() 创建全新临时测试库 + 登录 + 发布商品
+ N 个买家意向，互不污染（数据库写系统临时目录，与 test_smoke.py 方式一致）。
断言均带中文说明。

运行：python test_d_module.py
"""

import os
import re
import sys
import tempfile

# 数据库写到临时文件，避免污染交付目录；必须在 import db/main 之前设置
_TMP_DB = os.path.join(tempfile.mkdtemp(prefix="shop_d_test_"), "test.db")
os.environ["DB_PATH"] = _TMP_DB

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from db import get_conn, init_db  # noqa: E402

# 从提交成功页提取 8 位十六进制口令码（与 test_smoke.py 同一标记）
_TOKEN_RE = re.compile(r'token-code">([0-9a-f]{8})<')

# 每步结果收集
_results: list[tuple[str, bool, str]] = []


def step(name: str):
    """装饰器：包装单个测试用例，打印 PASS/FAIL 并统计。"""

    def deco(fn):
        def run():
            try:
                fn()
                _results.append((name, True, ""))
                print(f"[PASS] {name}")
            except AssertionError as e:
                _results.append((name, False, str(e)))
                print(f"[FAIL] {name} —— {e}")
            except Exception as e:  # noqa: BLE001
                _results.append((name, False, f"异常: {type(e).__name__}: {e}"))
                print(f"[FAIL] {name} —— 异常 {type(e).__name__}: {e}")

        return run

    return deco


# ---------------------------------------------------------------------------
# 环境搭建与查询工具
# ---------------------------------------------------------------------------

def fresh_env(n_intents: int = 0, buyers: str = "甲乙丙丁戊己"):
    """创建全新测试环境：临时库 + 卖家登录 + 发布商品 + n 个买家依次提交意向。

    返回 (client, tokens)，tokens 为 {买家名: 口令码}。
    每次调用都生成全新数据库，用例之间完全隔离。
    """
    tmp_dir = tempfile.mkdtemp(prefix="shop_d_env_")
    os.environ["DB_PATH"] = os.path.join(tmp_dir, "test.db")
    init_db()  # 新路径下重新建表 + 种子卖家

    client = TestClient(main.app)
    r = client.post(
        "/login", data={"username": "admin", "password": "admin12345"},
        follow_redirects=False,
    )
    assert r.status_code == 303, f"环境初始化：卖家应能登录，实际 {r.status_code}"

    r = client.post(
        "/admin/product",
        data={"name": "队列冻结边界测试商品", "description": "自测专用", "price": "9.90"},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, "环境初始化：商品应发布成功并进入在售"

    tokens: dict[str, str] = {}
    for i in range(n_intents):
        name = f"买家{buyers[i]}"
        r = client.post(
            "/product/intent", data={"buyer_name": name, "phone": f"1380000000{i + 1}"}
        )
        m = _TOKEN_RE.search(r.text)
        assert m, f"环境初始化：{name} 提交意向应成功并获得口令码"
        tokens[name] = m.group(1)
    return client, tokens


def product_status() -> str:
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT status FROM product ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return row["status"] if row else "none"
    finally:
        conn.close()


def intent_status(token: str) -> str | None:
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT status FROM intent WHERE token_code = ?", (token,)
        ).fetchone()
        return row["status"] if row else None
    finally:
        conn.close()


def intent_id_by_name(name: str) -> int | None:
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT id FROM intent WHERE buyer_name = ? ORDER BY id DESC LIMIT 1",
            (name,),
        ).fetchone()
        return row["id"] if row else None
    finally:
        conn.close()


# ===========================================================================
# D 组（队列与撤销）
# ===========================================================================

@step("D-1 后台意向人列表按提交时间正序（甲→乙→丙）")
def t_d1_queue_order():
    client, _ = fresh_env(3)
    r = client.get("/admin")
    assert r.status_code == 200
    order = [r.text.find(n) for n in ("买家甲", "买家乙", "买家丙")]
    assert all(i >= 0 for i in order), f"三个买家都应出现在后台列表中: {order}"
    assert order == sorted(order), f"应按提交时间正序排列（甲→乙→丙）: {order}"


@step("D-2 非队首进入交易被拒，后台仅队首有可用入口")
def t_d2_non_head_rejected():
    client, tokens = fresh_env(3)
    # 后台页面：可点击的「进入交易」按钮应只有队首 1 个
    r = client.get("/admin")
    clickable = r.text.count('btn-primary btn-sm">进入交易</button>')
    assert clickable == 1, f"后台仅队首应有可用的「进入交易」入口，实际 {clickable} 个"
    # 直接调用接口：第 2 位买家进入交易应被拒绝
    second_id = intent_id_by_name("买家乙")
    r2 = client.post(f"/admin/intent/{second_id}/enter", follow_redirects=True)
    assert "仅队首" in r2.text, "直接调用进入交易接口，非队首应被拒绝"
    assert product_status() == "on_sale", "被拒后商品不应被冻结"
    assert intent_status(tokens["买家乙"]) == "pending", "非队首意向应保持排队状态"


@step("D-3 正确口令码撤销成功、错误口令码被拒、撤销后从队列消失")
def t_d3_cancel_by_token():
    client, tokens = fresh_env(3)
    # 错误口令码：应被拒绝
    r = client.post("/cancel", data={"token_code": "00000000"})
    assert "口令码无效" in r.text, "错误口令码应被拒绝"
    # 正确口令码：应撤销成功
    r2 = client.post("/cancel", data={"token_code": tokens["买家乙"]})
    assert "已撤销" in r2.text, "正确口令码应撤销成功"
    assert intent_status(tokens["买家乙"]) == "cancelled", "被撤意向状态应为 cancelled"
    # 撤销后：后台列表不再显示该买家，其余保留
    r3 = client.get("/admin")
    assert r3.text.find("买家乙") == -1, "撤销后该意向不应再出现在后台意向人列表"
    assert r3.text.find("买家甲") >= 0 and r3.text.find("买家丙") >= 0, \
        "其余意向应保留在队列中"


@step("D-4 撤销第 2 位后，原第 3 位顺位前移为第 2 位（进入交易时验证顺位）")
def t_d4_promote_after_cancel():
    client, tokens = fresh_env(3)
    client.post("/cancel", data={"token_code": tokens["买家乙"]})
    # 队列顺序：甲（第 1 位）→ 丙（第 2 位）
    r = client.get("/admin")
    pos_jia, pos_bing = r.text.find("买家甲"), r.text.find("买家丙")
    assert pos_jia >= 0 and pos_bing > pos_jia, \
        f"撤销乙后队列应为 甲→丙，丙位于第 2 位: ({pos_jia}, {pos_bing})"
    # 进入交易时验证顺位：丙虽已是第 2 位，仍不可越过队首甲进入交易
    bing_id = intent_id_by_name("买家丙")
    r2 = client.post(f"/admin/intent/{bing_id}/enter", follow_redirects=True)
    assert "仅队首" in r2.text, "原第 3 位（现为第 2 位）仍不可越过队首进入交易，验证其顺位为第 2"
    assert product_status() == "on_sale", "被拒后商品应保持在售"


@step("D-5 队首交易失败后，原第 2 位自动递补为队首并可交易，商品再次冻结")
def t_d5_auto_promote_after_fail():
    client, tokens = fresh_env(2)
    head_id = intent_id_by_name("买家甲")
    r = client.post(f"/admin/intent/{head_id}/enter", follow_redirects=True)
    assert "已进入交易" in r.text, "队首甲应能进入交易"
    assert product_status() == "frozen", "进入交易后商品应自动冻结"
    r2 = client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert "下一位已自动递补" in r2.text, "标记失败后应提示自动递补"
    assert intent_status(tokens["买家甲"]) == "failed", "原队首甲应转为失败"
    assert intent_status(tokens["买家乙"]) == "trading", "原第 2 位乙应自动递补为交易中（新队首）"
    assert product_status() == "frozen", "递补后商品应再次处于冻结状态"


# ===========================================================================
# F 组（冻结解冻）
# ===========================================================================

@step("F-1 卖家选定队首进入交易，商品自动 在售→已冻结")
def t_f1_auto_freeze_on_enter():
    client, _ = fresh_env(1)
    assert product_status() == "on_sale", "前置：商品应处于在售状态"
    head_id = intent_id_by_name("买家甲")
    r = client.post(f"/admin/intent/{head_id}/enter", follow_redirects=True)
    assert "已进入交易" in r.text, "队首应能进入交易"
    assert product_status() == "frozen", "商品应由在售自动转为已冻结"


@step("F-2 手动冻结与自动冻结是同一个状态（接口返回状态一致）")
def t_f2_same_frozen_state():
    client, _ = fresh_env(1)
    # 路径一：手动冻结
    client.post("/admin/product/freeze", follow_redirects=True)
    manual_status = product_status()
    assert manual_status == "frozen", "手动冻结后商品应为已冻结"
    # 路径二：解冻后由队首进入交易触发自动冻结
    client.post("/admin/product/unfreeze", follow_redirects=True)
    head_id = intent_id_by_name("买家甲")
    client.post(f"/admin/intent/{head_id}/enter", follow_redirects=True)
    auto_status = product_status()
    assert auto_status == "frozen", "进入交易自动冻结后商品应为已冻结"
    assert manual_status == auto_status, \
        f"手动冻结与自动冻结应返回同一状态: 手动={manual_status} vs 自动={auto_status}"


@step("F-3 冻结期买家页仍显示商品但提示「商品交易中」")
def t_f3_frozen_visible_to_buyer():
    client, _ = fresh_env(0)
    client.post("/admin/product/freeze", follow_redirects=True)
    r = client.get("/product")
    assert r.status_code == 200
    assert "队列冻结边界测试商品" in r.text, "冻结期商品对买家仍应可见"
    assert "商品交易中" in r.text, "冻结期买家页应显示「商品交易中」提示"


@step("F-4 冻结期提交新意向接口拒绝，且数据库无新意向")
def t_f4_no_intent_when_frozen():
    client, _ = fresh_env(0)
    client.post("/admin/product/freeze", follow_redirects=True)
    r = client.post(
        "/product/intent", data={"buyer_name": "买家丁", "phone": "13800000004"}
    )
    assert "商品交易中" in r.text and "不接受" in r.text, "冻结期提交意向接口应返回拒绝"
    conn = get_conn()
    try:
        cnt = conn.execute("SELECT COUNT(*) AS c FROM intent").fetchone()["c"]
    finally:
        conn.close()
    assert cnt == 0, f"冻结期提交的意向不应写入数据库，实际 {cnt} 条"


@step("F-5 手动解冻恢复在售可收新意向；交易失败自动递补，队列空后恢复在售")
def t_f5_unfreeze_and_promote_chain():
    client, _ = fresh_env(0)
    # 手动冻结 → 手动解冻 → 恢复在售
    client.post("/admin/product/freeze", follow_redirects=True)
    assert product_status() == "frozen", "前置：手动冻结应生效"
    client.post("/admin/product/unfreeze", follow_redirects=True)
    assert product_status() == "on_sale", "手动解冻后商品应恢复在售"
    # 解冻后新意向可提交（两个买家，构成队列）
    r = client.post(
        "/product/intent", data={"buyer_name": "买家丁", "phone": "13800000004"}
    )
    m_ding = _TOKEN_RE.search(r.text)
    assert m_ding, "解冻后新意向应可提交"
    r = client.post(
        "/product/intent", data={"buyer_name": "买家戊", "phone": "13800000005"}
    )
    m_wu = _TOKEN_RE.search(r.text)
    assert m_wu, "解冻后第二个新意向应可提交"
    # 丁（队首）进入交易 → 标记失败 → 戊自动递补，商品保持冻结
    ding_id = intent_id_by_name("买家丁")
    client.post(f"/admin/intent/{ding_id}/enter", follow_redirects=True)
    assert product_status() == "frozen", "丁进入交易后商品应自动冻结"
    r2 = client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert "下一位已自动递补" in r2.text, "丁标记失败后戊应自动递补"
    assert intent_status(m_ding.group(1)) == "failed", "丁应转为失败"
    assert intent_status(m_wu.group(1)) == "trading", "戊应递补为交易中"
    # 戊也标记失败 → 队列为空 → 商品恢复在售
    r3 = client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert "恢复在售" in r3.text, "队列转空后标记失败，商品应恢复在售"
    assert product_status() == "on_sale", "队列为空时商品应恢复在售状态"


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main_run() -> int:
    cases = [
        t_d1_queue_order, t_d2_non_head_rejected, t_d3_cancel_by_token,
        t_d4_promote_after_cancel, t_d5_auto_promote_after_fail,
        t_f1_auto_freeze_on_enter, t_f2_same_frozen_state,
        t_f3_frozen_visible_to_buyer, t_f4_no_intent_when_frozen,
        t_f5_unfreeze_and_promote_chain,
    ]
    for fn in cases:
        fn()

    passed = sum(1 for _, ok, _ in _results if ok)
    failed = len(_results) - passed
    print("\n" + "=" * 60)
    print(f"周到模块自测：{passed} 通过 / {failed} 失败（共 {len(_results)} 项）")
    if failed:
        print("失败项：")
        for name, ok, msg in _results:
            if not ok:
                print(f"  - {name}: {msg}")
    print("=" * 60)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main_run())

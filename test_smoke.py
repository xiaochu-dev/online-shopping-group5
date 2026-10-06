# -*- coding: utf-8 -*-
"""
test_smoke.py —— 冒烟测试（fastapi.testclient.TestClient）
====================================================
按业务流程顺序走完整链路：
登录成功/失败 → 发布校验与发布 → 第二件发布被拒 → 3 个买家提交意向 →
冻结期提交被拒（手动冻结→解冻恢复）→ 后台意向人列表正序 → 凭口令码撤销中间一个 →
队首进入交易（商品冻结）→ 标记失败（下一位自动递补冻结）→ 标记成功（商品下架、其余转失败）→
历史列表与详情字段完整 → 改密码（错原密码拒绝、7 位拒绝、合法成功，测完改回）。

运行：python test_smoke.py
"""

import os
import sys
import tempfile

# 数据库写到临时文件，避免污染交付目录；必须在 import db/main 之前设置
_TMP_DB = os.path.join(tempfile.mkdtemp(prefix="shop_mvp_test_"), "test.db")
os.environ["DB_PATH"] = _TMP_DB

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from db import get_conn  # noqa: E402

client = TestClient(main.app)

PNG_BYTES = main._make_png(1, 1, (255, 0, 0))  # 1x1 PNG 字节流模拟图片上传

# 每步结果收集
_results: list[tuple[str, bool, str]] = []


def step(name: str):
    """装饰器：包装单个测试步骤，打印 PASS/FAIL 并统计。"""

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


def product_status() -> str:
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT status FROM product ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return row["status"] if row else "none"
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# 1. 登录
# ---------------------------------------------------------------------------

@step("登录：未登录访问后台重定向到 /login")
def t_unauth_redirect():
    c = TestClient(main.app)  # 不带 cookie 的新会话
    r = c.get("/admin", follow_redirects=False)
    assert r.status_code == 303, f"期望 303，得到 {r.status_code}"
    assert r.headers["location"] == "/login", f"期望重定向到 /login，得到 {r.headers['location']}"


@step("登录：错误密码被拒绝")
def t_login_fail():
    r = client.post(
        "/login", data={"username": "admin", "password": "wrong-pass"}, follow_redirects=False
    )
    assert r.status_code == 200, f"期望 200（回登录页），得到 {r.status_code}"
    assert "用户名或密码错误" in r.text, "响应应包含错误提示"


@step("登录：正确账号密码成功进入后台")
def t_login_ok():
    r = client.post(
        "/login", data={"username": "admin", "password": "admin12345"}, follow_redirects=False
    )
    assert r.status_code == 303, f"期望 303，得到 {r.status_code}"
    assert "seller_session" in r.headers.get("set-cookie", ""), "应签发会话 cookie"
    r2 = client.get("/admin")
    assert r2.status_code == 200
    assert "卖家后台" in r2.text, "后台页面应正常渲染"


# ---------------------------------------------------------------------------
# 2. 发布商品
# ---------------------------------------------------------------------------

@step("发布：名称缺失被拒")
def t_publish_no_name():
    r = client.post("/admin/product", data={"name": "", "price": "9.90"}, follow_redirects=True)
    assert "商品名称不能为空" in r.text, "应提示名称必填"


@step("发布：价格为 0 被拒")
def t_publish_zero_price():
    r = client.post(
        "/admin/product",
        data={"name": "测试商品", "price": "0"},
        follow_redirects=True,
    )
    assert "价格必须大于 0" in r.text, "应提示价格必须大于 0"


@step("发布：图片格式非法被拒")
def t_publish_bad_image():
    r = client.post(
        "/admin/product",
        data={"name": "测试商品", "price": "9.90"},
        files={"image": ("a.gif", PNG_BYTES, "image/gif")},
        follow_redirects=True,
    )
    assert "图片仅支持 JPG/PNG" in r.text, "应拒绝非 JPG/PNG 图片"


@step("发布：合法商品发布成功并进入在售")
def t_publish_ok():
    r = client.post(
        "/admin/product",
        data={"name": "测试手机壳", "description": "九成新", "price": "9.90"},
        files={"image": ("t.png", PNG_BYTES, "image/png")},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, f"应提示发布成功；实际响应含: {r.text[:200]}"
    assert product_status() == "on_sale", f"商品应处于 on_sale，实际 {product_status()}"


@step("发布：第二件商品被拒（同一时间仅一件在售）")
def t_publish_second_rejected():
    r = client.post(
        "/admin/product",
        data={"name": "第二件", "price": "5.00"},
        follow_redirects=True,
    )
    assert "同一时间仅允许一件" in r.text, "应拒绝第二件商品发布"
    assert product_status() == "on_sale", "商品状态不应变化"


# ---------------------------------------------------------------------------
# 3. 买家提交意向
# ---------------------------------------------------------------------------

@step("买家页：无需登录可见在售商品")
def t_buyer_page():
    c = TestClient(main.app)
    r = c.get("/product")
    assert r.status_code == 200
    assert "测试手机壳" in r.text and "9.90" in r.text, "买家页应展示商品与价格"


tokens: dict[str, str] = {}  # buyer_name -> token_code


@step("提交意向：3 个买家提交成功并各自获得独立口令码")
def t_submit_intents():
    names_phones = [
        ("买家甲", "13800000001"),
        ("买家乙", "13800000002"),
        ("买家丙", "13800000003"),
    ]
    seen_tokens = set()
    for name, phone in names_phones:
        r = client.post(
            "/product/intent", data={"buyer_name": name, "phone": phone}
        )
        assert r.status_code == 200
        assert "意向提交成功" in r.text, f"{name} 提交应成功"
        assert "你的口令码" in r.text, "应当场显示口令码"
        # 从响应中提取 8 位十六进制口令码
        import re

        m = re.search(r"token-code\">([0-9a-f]{8})<", r.text)
        assert m, f"{name} 的口令码应可在页面中找到"
        tokens[name] = m.group(1)
        assert tokens[name] not in seen_tokens, "各意向口令码应互不相同"
        seen_tokens.add(tokens[name])


@step("提交意向：必填缺失被拒")
def t_intent_required():
    r = client.post("/product/intent", data={"buyer_name": "", "phone": "123"})
    assert "姓名与联系电话均为必填项" in r.text, "应提示必填"


# ---------------------------------------------------------------------------
# 4. 冻结 / 解冻开关（FR-06）
# ---------------------------------------------------------------------------

@step("冻结：手动冻结后买家仍可见但显示商品交易中")
def t_manual_freeze():
    r = client.post("/admin/product/freeze", follow_redirects=True)
    assert "商品已冻结" in r.text
    assert product_status() == "frozen"
    r2 = client.get("/product")
    assert "商品交易中" in r2.text, "买家页应显示商品交易中"
    assert "测试手机壳" in r2.text, "冻结期商品对买家仍可见"


@step("冻结：冻结期提交意向被拒")
def t_intent_rejected_when_frozen():
    r = client.post(
        "/product/intent", data={"buyer_name": "买家丁", "phone": "13800000004"}
    )
    assert "商品交易中" in r.text and "不接受" in r.text, "冻结期应拒绝新意向"
    conn = get_conn()
    try:
        cnt = conn.execute(
            "SELECT COUNT(*) AS c FROM intent WHERE buyer_name = '买家丁'"
        ).fetchone()["c"]
    finally:
        conn.close()
    assert cnt == 0, "冻结期提交的意向不应入库"


@step("解冻：手动解冻后恢复在售并可收新意向")
def t_manual_unfreeze():
    r = client.post("/admin/product/unfreeze", follow_redirects=True)
    assert "商品已解冻" in r.text
    assert product_status() == "on_sale"
    r2 = client.post(
        "/product/intent", data={"buyer_name": "买家戊", "phone": "13800000005"}
    )
    assert "意向提交成功" in r2.text, "解冻后应可重新提交意向"


# ---------------------------------------------------------------------------
# 5. 后台意向人列表正序（FR-05）
# ---------------------------------------------------------------------------

@step("后台意向人列表：按提交时间正序、仅队首可点进入交易")
def t_admin_queue_order():
    r = client.get("/admin")
    assert r.status_code == 200
    # 买家甲 → 买家乙 → 买家丙 → 买家戊 的出现顺序
    order = [r.text.find(n) for n in ("买家甲", "买家乙", "买家丙", "买家戊")]
    assert all(i >= 0 for i in order), f"四位买家都应出现在列表中: {order}"
    assert order == sorted(order), f"应按提交时间正序排列: {order}"
    # 可点的「进入交易」按钮仅 1 个（队首），其余为禁用按钮
    assert r.text.count('btn-primary btn-sm">进入交易</button>') == 1, "仅队首的「进入交易」按钮可点"
    assert r.text.count('disabled>进入交易</button>') == len(order) - 1, "非队首按钮应禁用"


# ---------------------------------------------------------------------------
# 6. 凭口令码撤销（D-3）
# ---------------------------------------------------------------------------

@step("撤销：凭口令码撤销中间意向，队列位次前移")
def t_cancel_middle():
    r = client.post("/cancel", data={"token_code": tokens["买家乙"]})
    assert "已撤销" in r.text, "应提示撤销成功"
    conn = get_conn()
    try:
        st = conn.execute(
            "SELECT status FROM intent WHERE token_code = ?", (tokens["买家乙"],)
        ).fetchone()["status"]
    finally:
        conn.close()
    assert st == "cancelled", f"意向应转为 cancelled，实际 {st}"
    r2 = client.get("/admin")
    order = [r2.text.find(n) for n in ("买家甲", "买家丙", "买家戊")]
    assert order == sorted(order) and r2.text.find("买家乙") == -1, "买家乙应移出队列"


@step("撤销：错误口令码被拒")
def t_cancel_bad_token():
    r = client.post("/cancel", data={"token_code": "deadbeef"})
    assert "口令码无效" in r.text, "无效口令码应被拒绝"


# ---------------------------------------------------------------------------
# 7. 进入交易（E-2 自动冻结）
# ---------------------------------------------------------------------------

@step("进入交易：队首意向进入交易，商品自动冻结")
def t_enter_trading():
    conn = get_conn()
    try:
        head = conn.execute(
            "SELECT id, buyer_name FROM intent WHERE status = 'pending' "
            "ORDER BY created_at ASC, id ASC LIMIT 1"
        ).fetchone()
    finally:
        conn.close()
    assert head["buyer_name"] == "买家甲", f"队首应为买家甲，实际 {head['buyer_name']}"
    r = client.post(f"/admin/intent/{head['id']}/enter", follow_redirects=True)
    assert "已进入交易" in r.text
    assert product_status() == "frozen", "商品应自动冻结"
    conn = get_conn()
    try:
        st = conn.execute(
            "SELECT status FROM intent WHERE id = ?", (head["id"],)
        ).fetchone()["status"]
    finally:
        conn.close()
    assert st == "trading", "该意向应转为交易中"


@step("进入交易：非队首被拒")
def t_enter_trading_non_head():
    conn = get_conn()
    try:
        other = conn.execute(
            "SELECT id FROM intent WHERE status = 'pending' "
            "ORDER BY created_at ASC, id ASC LIMIT 1"
        ).fetchone()
    finally:
        conn.close()
    r = client.post(f"/admin/intent/{other['id']}/enter", follow_redirects=True)
    assert "仅队首" in r.text or "在交易中" in r.text, "非队首进入交易应被拒绝"


# ---------------------------------------------------------------------------
# 8. 标记失败 → 自动递补
# ---------------------------------------------------------------------------

@step("标记失败：队首转失败，下一位自动递补、商品再次冻结")
def t_mark_fail():
    r = client.post(
        "/admin/product/mark", data={"result": "fail"}, follow_redirects=True
    )
    assert "下一位已自动递补" in r.text, "应提示自动递补"
    conn = get_conn()
    try:
        head = conn.execute(
            "SELECT buyer_name, status FROM intent WHERE status = 'trading' LIMIT 1"
        ).fetchone()
        failed = conn.execute(
            "SELECT status FROM intent WHERE token_code = ?", (tokens["买家甲"],)
        ).fetchone()["status"]
    finally:
        conn.close()
    assert failed == "failed", "队首（买家甲）应转为失败"
    assert head is not None and head["buyer_name"] == "买家丙", f"买家丙应递补为交易中，实际 {head}"
    assert product_status() == "frozen", "递补后商品应再次冻结"


@step("交易中：不允许解冻")
def t_unfreeze_blocked_when_trading():
    r = client.post("/admin/product/unfreeze", follow_redirects=True)
    assert "无法解冻" in r.text, "交易进行中解冻应被拒绝"


# ---------------------------------------------------------------------------
# 9. 标记成功 → 下架入历史
# ---------------------------------------------------------------------------

@step("标记成功：商品下架入历史，其余意向转失败")
def t_mark_success():
    r = client.post(
        "/admin/product/mark", data={"result": "success"}, follow_redirects=True
    )
    assert "商品已下架进入历史" in r.text
    assert product_status() == "delisted", "商品应转为已下架"
    conn = get_conn()
    try:
        st_b = conn.execute(
            "SELECT status FROM intent WHERE buyer_name = '买家丙'"
        ).fetchone()["status"]
        st_c = conn.execute(
            "SELECT status FROM intent WHERE buyer_name = '买家戊'"
        ).fetchone()["status"]
        prod = conn.execute(
            "SELECT traded_at, final_result FROM product "
            "WHERE status = 'delisted' ORDER BY id DESC LIMIT 1"
        ).fetchone()
    finally:
        conn.close()
    assert st_b == "success", "队首（买家丙）应转为成功"
    assert st_c == "failed", "其余排队意向（买家戊）应转为失败"
    assert prod["final_result"] == "成功" and prod["traded_at"], "历史商品应记录结果与交易时间"


@step("下架后：买家页显示暂无商品在售，可发布新商品")
def t_after_delist():
    r = client.get("/product")
    assert "暂无商品在售" in r.text
    r2 = client.post(
        "/admin/product", data={"name": "下一件商品", "price": "20.00"}, follow_redirects=True
    )
    assert "商品发布成功" in r2.text, "下架后应允许发布下一件商品"


# ---------------------------------------------------------------------------
# 10. 历史（FR-08）
# ---------------------------------------------------------------------------

@step("历史列表：已下架商品倒序展示、字段完整")
def t_history_list():
    r = client.get("/admin/history")
    assert r.status_code == 200
    assert "测试手机壳" in r.text and "9.90" in r.text, "商品名与价格应展示"
    assert "成功" in r.text, "最终结果应展示"
    # 新发布的商品尚未下架，不应出现在历史
    assert "下一件商品" not in r.text, "在售商品不应出现在历史"


@step("历史详情：意向人级字段完整（姓名、电话、提交时间、结果）")
def t_history_detail():
    conn = get_conn()
    try:
        pid = conn.execute(
            "SELECT id FROM product WHERE status = 'delisted' ORDER BY id DESC LIMIT 1"
        ).fetchone()["id"]
    finally:
        conn.close()
    r = client.get(f"/admin/history/{pid}")
    assert r.status_code == 200
    for text in ("买家甲", "买家乙", "买家丙", "买家戊", "13800000001", "已撤销", "失败", "成功"):
        assert text in r.text, f"历史详情应包含: {text}"


@step("历史分页：每页 5 条与页码导航逻辑正确")
def t_history_paging():
    # 当前仅 1 条历史；分页参数不应报错
    r = client.get("/admin/history?page=2")
    assert r.status_code == 200
    assert "第 2 / 1 页" not in r.text, "页码应被夹取到有效范围"


# ---------------------------------------------------------------------------
# 11. 改密码（FR-01-4/5）
# ---------------------------------------------------------------------------

@step("改密码：原密码错误被拒")
def t_change_pwd_wrong_old():
    r = client.post(
        "/admin/change_password",
        data={"old_password": "wrong!", "new_password": "newpass123"},
        follow_redirects=True,
    )
    assert "原密码错误" in r.text, "应拒绝错误原密码"


@step("改密码：新密码不足 8 位被拒")
def t_change_pwd_short():
    r = client.post(
        "/admin/change_password",
        data={"old_password": "admin12345", "new_password": "1234567"},
        follow_redirects=True,
    )
    assert "8" in r.text and "不得少于" in r.text, "7 位新密码应被拒绝"


@step("改密码：合法修改成功且新密码可登录")
def t_change_pwd_ok():
    r = client.post(
        "/admin/change_password",
        data={"old_password": "admin12345", "new_password": "newpass123"},
        follow_redirects=True,
    )
    assert "密码修改成功" in r.text
    c = TestClient(main.app)
    r2 = c.post("/login", data={"username": "admin", "password": "newpass123"}, follow_redirects=False)
    assert r2.status_code == 303, "新密码应可登录"
    r3 = c.post("/login", data={"username": "admin", "password": "admin12345"}, follow_redirects=False)
    assert r3.status_code == 200 and "用户名或密码错误" in r3.text, "旧密码应已失效"


@step("改密码：测完改回 admin12345")
def t_change_pwd_restore():
    r = client.post(
        "/admin/change_password",
        data={"old_password": "newpass123", "new_password": "admin12345"},
        follow_redirects=True,
    )
    assert "密码修改成功" in r.text
    c = TestClient(main.app)
    r2 = c.post("/login", data={"username": "admin", "password": "admin12345"}, follow_redirects=False)
    assert r2.status_code == 303, "admin12345 应恢复可登录"


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main_run() -> int:
    steps = [
        t_unauth_redirect, t_login_fail, t_login_ok,
        t_publish_no_name, t_publish_zero_price, t_publish_bad_image,
        t_publish_ok, t_publish_second_rejected,
        t_buyer_page, t_submit_intents, t_intent_required,
        t_manual_freeze, t_intent_rejected_when_frozen, t_manual_unfreeze,
        t_admin_queue_order,
        t_cancel_middle, t_cancel_bad_token,
        t_enter_trading, t_enter_trading_non_head,
        t_mark_fail, t_unfreeze_blocked_when_trading,
        t_mark_success, t_after_delist,
        t_history_list, t_history_detail, t_history_paging,
        t_change_pwd_wrong_old, t_change_pwd_short, t_change_pwd_ok, t_change_pwd_restore,
    ]
    for fn in steps:
        fn()

    passed = sum(1 for _, ok, _ in _results if ok)
    failed = len(_results) - passed
    print("\n" + "=" * 60)
    print(f"冒烟测试结果：{passed} 通过 / {failed} 失败（共 {len(_results)} 项）")
    if failed:
        print("失败项：")
        for name, ok, msg in _results:
            if not ok:
                print(f"  - {name}: {msg}")
    print("=" * 60)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main_run())

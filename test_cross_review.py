# -*- coding: utf-8 -*-
"""
test_cross_review.py —— 交叉测试（周到 → 胡成溯：卖家后台 FR-01/05/07/08）
====================================================
闭环互测规则：周到（产品经理）测胡成溯（组长）署名的卖家后台模块。
独立视角按《需求规格说明书》第 6 章「MVP 功能验收清单」14 条逐条对照，
不是重复运行 test_smoke.py 已有用例。

覆盖清单（每条一个测试函数，函数名带清单序号）：
  1  卖家登录      2  商品发布      3  商品浏览      4  提交意向
  5  排队规则      6  撤销意向      7  查看意向人    8  冻结
  9  解冻          10 标记成功      11 标记失败      12 历史记录
  13 修改密码      14 范围核对

运行：python test_cross_review.py
说明：测试库写到系统临时目录（DB_PATH 环境变量），不污染交付目录；
      全程不上传图片（走占位图），不产生 uploads 落盘。
"""

import os
import re
import sys
import tempfile

# 数据库写到临时文件，必须在 import db/main 之前设置
_TMP_DB = os.path.join(tempfile.mkdtemp(prefix="shop_mvp_cross_test_"), "test.db")
os.environ["DB_PATH"] = _TMP_DB

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:  # Windows 控制台中文输出
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # noqa: BLE001
    pass

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from db import get_conn  # noqa: E402

client = TestClient(main.app)  # 卖家会话（登录后携带 cookie）

P1_NAME, P1_DESC, P1_PRICE = "交叉测试商品", "交叉测试专用描述文本", "12.50"
P2_NAME, P2_PRICE = "交叉测试商品二号", "20.00"

_results: list[tuple[str, bool, str]] = []


def checklist(no: int, item: str):
    """装饰器：包装单条验收项测试，打印 [PASS/FAIL] 并统计。"""

    def deco(fn):
        def run():
            try:
                fn()
                _results.append((no, True, ""))
                print(f"[PASS] 清单{no} {item}：通过")
            except AssertionError as e:
                _results.append((no, False, str(e)))
                print(f"[FAIL] 清单{no} {item}：{e}")
            except Exception as e:  # noqa: BLE001
                _results.append((no, False, f"异常 {type(e).__name__}: {e}"))
                print(f"[FAIL] 清单{no} {item}：异常 {type(e).__name__}: {e}")

        return run

    return deco


# ---------------------------------------------------------------------------
# 数据库小工具
# ---------------------------------------------------------------------------

def db_one(sql: str, params: tuple = ()):
    conn = get_conn()
    try:
        return conn.execute(sql, params).fetchone()
    finally:
        conn.close()


def db_exec(sql: str, params: tuple = ()) -> None:
    conn = get_conn()
    try:
        conn.execute(sql, params)
        conn.commit()
    finally:
        conn.close()


def product_status() -> str:
    row = db_one("SELECT status FROM product ORDER BY id DESC LIMIT 1")
    return row["status"] if row else "none"


def latest_product_id() -> int:
    return db_one("SELECT id FROM product ORDER BY id DESC LIMIT 1")["id"]


def intent_id_by_name(name: str) -> int:
    row = db_one(
        "SELECT id FROM intent WHERE buyer_name = ? ORDER BY id DESC LIMIT 1", (name,)
    )
    assert row is not None, f"意向记录 {name} 应存在"
    return row["id"]


def token_of(name: str) -> str:
    row = db_one(
        "SELECT token_code FROM intent WHERE buyer_name = ? ORDER BY id DESC LIMIT 1",
        (name,),
    )
    assert row is not None, f"意向记录 {name} 应存在"
    return row["token_code"]


# ---------------------------------------------------------------------------
# 1. 卖家登录（FR-01）
# ---------------------------------------------------------------------------

@checklist(1, "卖家登录")
def test_01_login():
    # 未登录访问 /admin 被重定向
    c = TestClient(main.app)
    r = c.get("/admin", follow_redirects=False)
    assert r.status_code == 303, f"未登录访问 /admin 期望 303，得到 {r.status_code}"
    assert r.headers["location"] == "/login", f"应重定向到 /login，实际 {r.headers['location']}"
    # 错误密码被拒
    r = c.post("/login", data={"username": "admin", "password": "wrong-password-666"})
    assert r.status_code == 200, f"错误密码应回登录页 200，得到 {r.status_code}"
    assert "用户名或密码错误" in r.text, "错误密码应提示「用户名或密码错误」"
    # 正确凭据可进入后台
    r = client.post(
        "/login", data={"username": "admin", "password": "admin12345"}, follow_redirects=False
    )
    assert r.status_code == 303, f"正确凭据期望 303，得到 {r.status_code}"
    assert "seller_session" in r.headers.get("set-cookie", ""), "登录成功应签发会话 cookie"
    r2 = client.get("/admin")
    assert r2.status_code == 200 and "卖家后台" in r2.text, "登录后应能进入后台页面"


# ---------------------------------------------------------------------------
# 2. 商品发布（FR-02，主要验证与黄程宇模块的接口契约，简测）
# ---------------------------------------------------------------------------

@checklist(2, "商品发布")
def test_02_publish():
    r = client.post(
        "/admin/product",
        data={"name": P1_NAME, "description": P1_DESC, "price": P1_PRICE},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, f"发布应成功，响应片段: {r.text[:200]}"
    assert product_status() == "on_sale", f"发布后商品应进入在售状态，实际 {product_status()}"


# ---------------------------------------------------------------------------
# 3. 商品浏览（FR-03-1）
# ---------------------------------------------------------------------------

@checklist(3, "商品浏览")
def test_03_browse():
    c = TestClient(main.app)  # 买家无需登录
    r = c.get("/product")
    assert r.status_code == 200
    assert P1_NAME in r.text, "买家页应展示商品名称"
    assert P1_DESC in r.text, "买家页应展示商品描述"
    assert P1_PRICE in r.text, "买家页应展示商品价格（两位小数）"


# ---------------------------------------------------------------------------
# 4. 提交意向（FR-03-4~8）
# ---------------------------------------------------------------------------

@checklist(4, "提交意向")
def test_04_submit_intent():
    # 姓名缺失被拒
    r = client.post("/product/intent", data={"buyer_name": "", "phone": "13800000001"})
    assert "姓名与联系电话均为必填项" in r.text, "缺姓名应提示必填"
    # 电话缺失被拒
    r = client.post("/product/intent", data={"buyer_name": "买家甲", "phone": ""})
    assert "姓名与联系电话均为必填项" in r.text, "缺电话应提示必填"
    # 缺失项不应入库
    cnt = db_one("SELECT COUNT(*) AS c FROM intent")["c"]
    assert cnt == 0, f"校验失败的意向不应入库，实际已有 {cnt} 条"
    # 合法提交：口令码生成并当场显示
    r = client.post("/product/intent", data={"buyer_name": "买家甲", "phone": "13800000001"})
    assert "意向提交成功" in r.text, "合法提交应成功"
    m = re.search(r'token-code">([0-9a-f]{8})<', r.text)
    assert m, "口令码应当场显示（8 位十六进制）"
    assert m.group(1) == token_of("买家甲"), "页面显示的口令码应与库中一致"


# ---------------------------------------------------------------------------
# 5. 排队规则（FR-04-1/2/3）
# ---------------------------------------------------------------------------

@checklist(5, "排队规则")
def test_05_queue_rule():
    for name, phone in (("买家乙", "13800000002"), ("买家丙", "13800000003")):
        r = client.post("/product/intent", data={"buyer_name": name, "phone": phone})
        assert "意向提交成功" in r.text, f"{name} 提交应成功"
    r = client.get("/admin")
    # 按提交时间正序排列
    order = [r.text.find(n) for n in ("买家甲", "买家乙", "买家丙")]
    assert all(i >= 0 for i in order), f"三个意向都应出现在后台列表: {order}"
    assert order == sorted(order), f"应按提交时间正序排列: {order}"
    # 仅队首可进入交易：非队首请求被拒
    yi_id = intent_id_by_name("买家乙")
    r = client.post(f"/admin/intent/{yi_id}/enter", follow_redirects=True)
    assert "仅队首" in r.text, "非队首进入交易应被拒绝"
    # 后台仅队首的「进入交易」按钮可点，其余禁用
    r = client.get("/admin")
    assert r.text.count('btn-primary btn-sm">进入交易</button>') == 1, "仅队首按钮可点"
    assert r.text.count("disabled>进入交易</button>") == 2, "非队首按钮应禁用"


# ---------------------------------------------------------------------------
# 6. 撤销意向（FR-04-4~6）
# ---------------------------------------------------------------------------

@checklist(6, "撤销意向")
def test_06_cancel():
    token = token_of("买家乙")
    r = client.post("/cancel", data={"token_code": token})
    assert "已撤销" in r.text, "凭口令码撤销应成功"
    st = db_one("SELECT status FROM intent WHERE token_code = ?", (token,))["status"]
    assert st == "cancelled", f"撤销后意向应为 cancelled，实际 {st}"
    # 队列自动顺位前移：甲→丙（乙移出）
    r = client.get("/admin")
    order = [r.text.find(n) for n in ("买家甲", "买家丙")]
    assert all(i >= 0 for i in order) and order == sorted(order), f"甲丙应保持正序: {order}"
    assert r.text.find("买家乙") == -1, "被撤销的买家乙应移出队列"


# ---------------------------------------------------------------------------
# 7. 查看意向人（FR-05）
# ---------------------------------------------------------------------------

@checklist(7, "查看意向人")
def test_07_view_intents():
    r = client.get("/admin")
    assert r.status_code == 200
    for header in ("姓名", "联系电话", "提交时间"):
        assert header in r.text, f"后台列表应含「{header}」表头"
    for name, phone in (("买家甲", "13800000001"), ("买家丙", "13800000003")):
        assert name in r.text, f"列表应含姓名 {name}"
        assert phone in r.text, f"列表应含电话 {phone}"
    row = db_one("SELECT created_at FROM intent WHERE buyer_name = '买家甲'")
    assert row["created_at"] in r.text, "列表应展示意向提交时间"


# ---------------------------------------------------------------------------
# 8. 冻结（FR-06-1/2/4/5）
# ---------------------------------------------------------------------------

@checklist(8, "冻结")
def test_08_freeze():
    # 手动冻结生效
    r = client.post("/admin/product/freeze", follow_redirects=True)
    assert "商品已冻结" in r.text, "手动冻结应有成功反馈"
    assert product_status() == "frozen", f"冻结后状态应为 frozen，实际 {product_status()}"
    # 冻结期商品仍可见且显示「商品交易中」
    c = TestClient(main.app)
    r = c.get("/product")
    assert P1_NAME in r.text, "冻结期商品对买家仍可见"
    assert "商品交易中" in r.text, "冻结期买家页应显示「商品交易中」"
    # 冻结期不收新意向
    r = c.post("/product/intent", data={"buyer_name": "买家丁", "phone": "13800000004"})
    assert "商品交易中" in r.text, "冻结期提交应被拒绝并提示交易中"
    assert db_one("SELECT COUNT(*) AS c FROM intent WHERE buyer_name = '买家丁'")["c"] == 0, \
        "冻结期提交的意向不应入库"
    # 解冻还原，再验证「进入交易自动冻结」
    client.post("/admin/product/unfreeze", follow_redirects=True)
    assert product_status() == "on_sale", "验证前应先恢复在售"
    head_id = intent_id_by_name("买家甲")
    r = client.post(f"/admin/intent/{head_id}/enter", follow_redirects=True)
    assert "已进入交易" in r.text, "队首应可进入交易"
    assert product_status() == "frozen", "进入交易后商品应自动冻结"
    st = db_one("SELECT status FROM intent WHERE id = ?", (head_id,))["status"]
    assert st == "trading", f"进入交易后该意向应为 trading，实际 {st}"


# ---------------------------------------------------------------------------
# 9. 解冻（FR-06-6/7）
# ---------------------------------------------------------------------------

@checklist(9, "解冻")
def test_09_unfreeze():
    # 当前状态：甲在交易中、商品冻结。实现约定：交易进行中禁止解冻（见 services_state.unfreeze_product）
    r = client.post("/admin/product/unfreeze", follow_redirects=True)
    assert "无法解冻" in r.text, "交易进行中解冻应被拒绝（实现约定）"
    # 两次标记失败清空队列（甲失败→丙递补；丙失败→队列空恢复在售），回到「冻结但无交易」场景
    client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert product_status() == "on_sale", "队列清空后商品应恢复在售"
    # 核心验收：手动冻结 → 手动解冻 → 恢复在售并可收新意向
    client.post("/admin/product/freeze", follow_redirects=True)
    assert product_status() == "frozen"
    r = client.post("/admin/product/unfreeze", follow_redirects=True)
    assert "商品已解冻" in r.text, "手动解冻应有成功反馈"
    assert product_status() == "on_sale", f"解冻后应恢复在售，实际 {product_status()}"
    r = client.post("/product/intent", data={"buyer_name": "买家丁", "phone": "13800000004"})
    assert "意向提交成功" in r.text, "解冻后应可接收新意向"


# ---------------------------------------------------------------------------
# 10. 标记成功（FR-07-5）
# ---------------------------------------------------------------------------

@checklist(10, "标记成功")
def test_10_mark_success():
    head_id = intent_id_by_name("买家丁")
    r = client.post(f"/admin/intent/{head_id}/enter", follow_redirects=True)
    assert "已进入交易" in r.text
    r = client.post("/admin/product/mark", data={"result": "success"}, follow_redirects=True)
    assert "商品已下架进入历史" in r.text, "标记成功应提示下架入历史"
    assert product_status() == "delisted", f"商品应撤下转 delisted，实际 {product_status()}"
    row = db_one(
        "SELECT traded_at, final_result FROM product WHERE id = ?", (latest_product_id(),)
    )
    assert row["final_result"] == "成功" and row["traded_at"], "历史应记录最终结果与交易时间"
    st = db_one("SELECT status FROM intent WHERE id = ?", (head_id,))["status"]
    assert st == "success", f"队首意向应转 success，实际 {st}"
    c = TestClient(main.app)
    assert "暂无商品在售" in c.get("/product").text, "下架后买家页应显示暂无商品在售"


# ---------------------------------------------------------------------------
# 11. 标记失败（FR-07-6/7）
# ---------------------------------------------------------------------------

@checklist(11, "标记失败")
def test_11_mark_fail():
    # 发布第二件商品，两位买家排队，队首进入交易
    r = client.post(
        "/admin/product", data={"name": P2_NAME, "price": P2_PRICE}, follow_redirects=True
    )
    assert "商品发布成功" in r.text, "下架后应可发布新商品"
    for name, phone in (("买家戊", "13800000005"), ("买家己", "13800000006")):
        client.post("/product/intent", data={"buyer_name": name, "phone": phone})
    wu_id = intent_id_by_name("买家戊")
    client.post(f"/admin/intent/{wu_id}/enter", follow_redirects=True)
    assert product_status() == "frozen"
    # 标记失败 → 排队者自动递补（商品保持冻结）
    r = client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert "下一位已自动递补" in r.text, "标记失败应提示自动递补"
    st_wu = db_one("SELECT status FROM intent WHERE id = ?", (wu_id,))["status"]
    assert st_wu == "failed", f"队首应转 failed，实际 {st_wu}"
    ji_id = intent_id_by_name("买家己")
    st_ji = db_one("SELECT status FROM intent WHERE id = ?", (ji_id,))["status"]
    assert st_ji == "trading", f"排队者应自动递补为交易中，实际 {st_ji}"
    assert product_status() == "frozen", "递补后商品应保持冻结"
    # 再次标记失败 → 队列为空，商品恢复在售
    r = client.post("/admin/product/mark", data={"result": "fail"}, follow_redirects=True)
    assert "恢复在售" in r.text, "队列清空后标记失败应提示恢复在售"
    assert product_status() == "on_sale", f"商品应恢复在售，实际 {product_status()}"
    st_ji = db_one("SELECT status FROM intent WHERE id = ?", (ji_id,))["status"]
    assert st_ji == "failed", f"递补者交易失败后应转 failed，实际 {st_ji}"


# ---------------------------------------------------------------------------
# 12. 历史记录（FR-08）
# ---------------------------------------------------------------------------

@checklist(12, "历史记录")
def test_12_history():
    # 造 5 条历史（直接入库，traded_at 早于清单10产生的真实记录），共 6 条 → 第 2 页有 1 条
    for i in range(1, 6):
        db_exec(
            "INSERT INTO product (name, description, image_path, price, status, "
            "published_at, traded_at, final_result) VALUES (?, ?, 'placeholder.png', ?, "
            "'delisted', ?, ?, ?)",
            (f"历史测试商品{i:02d}", "", "1.00",
             f"2026-10-06 09:00:{i:02d}.000", f"2026-10-06 10:00:{i:02d}.000",
             "成功" if i % 2 else "失败"),
        )
    total = db_one("SELECT COUNT(*) AS c FROM product WHERE status = 'delisted'")["c"]
    assert total == 6, f"应共有 6 条历史（1 条真实 + 5 条构造），实际 {total}"
    # 列表页：倒序排列（交易时间降序，最新在前）
    r = client.get("/admin/history")
    assert r.status_code == 200
    for header in ("商品名称", "价格", "最终结果", "发布时间", "交易时间"):
        assert header in r.text, f"历史列表应含「{header}」列"
    pos = [r.text.find(n) for n in
           (P1_NAME, "历史测试商品05", "历史测试商品04", "历史测试商品03", "历史测试商品02")]
    assert all(i >= 0 for i in pos), f"第 1 页应有最新 5 条: {pos}"
    assert pos == sorted(pos), f"历史应按交易时间倒序排列: {pos}"
    assert "历史测试商品01" not in r.text, "第 6 条应翻页后才可见"
    assert "第 1 / 2 页" in r.text and "共 6 条" in r.text, "应显示页码与总数"
    # 翻页：第 2 页
    r2 = client.get("/admin/history?page=2")
    assert r2.status_code == 200
    assert "历史测试商品01" in r2.text, "第 2 页应显示最早 1 条"
    assert "历史测试商品02" not in r2.text, "第 2 页不应重复第 1 页内容"
    assert "第 2 / 2 页" in r2.text, "第 2 页页码应正确"
    # 详情页：商品级 + 意向人级字段完整
    pid = db_one("SELECT id FROM product WHERE name = ?", (P1_NAME,))["id"]
    r3 = client.get(f"/admin/history/{pid}")
    assert r3.status_code == 200
    for text in (P1_NAME, P1_DESC, P1_PRICE):  # 商品级：名称/描述/价格
        assert text in r3.text, f"详情页商品级字段应含 {text}"
    assert "发布时间" in r3.text and "交易时间" in r3.text, "详情页应含发布/交易时间"
    assert "成功" in r3.text, "详情页应含最终结果"
    for text in ("买家甲", "买家乙", "买家丙", "买家丁",
                 "13800000001", "13800000002", "13800000004"):  # 意向人级：姓名/电话
        assert text in r3.text, f"详情页意向人列表应含 {text}"
    for st_text in ("成功", "失败", "已撤销"):  # 意向人级：每人交易结果
        assert st_text in r3.text, f"详情页应展示每人交易结果（{st_text}）"


# ---------------------------------------------------------------------------
# 13. 修改密码（FR-01-4/5）
# ---------------------------------------------------------------------------

@checklist(13, "修改密码")
def test_13_change_password():
    # 原密码错误被拒
    r = client.post(
        "/admin/change_password",
        data={"old_password": "wrong-old-pwd", "new_password": "cross-pass-666"},
        follow_redirects=True,
    )
    assert "原密码错误" in r.text, "原密码错误应被拒绝"
    # 新密码不足 8 位被拒
    r = client.post(
        "/admin/change_password",
        data={"old_password": "admin12345", "new_password": "1234567"},
        follow_redirects=True,
    )
    assert "不得少于 8 位" in r.text, "7 位新密码应被拒绝"
    # 合法修改成功，新密码可登录、旧密码失效
    r = client.post(
        "/admin/change_password",
        data={"old_password": "admin12345", "new_password": "cross-pass-666"},
        follow_redirects=True,
    )
    assert "密码修改成功" in r.text, "合法修改应成功"
    c = TestClient(main.app)
    r2 = c.post("/login", data={"username": "admin", "password": "cross-pass-666"},
                follow_redirects=False)
    assert r2.status_code == 303, "改密后新密码应可登录"
    r3 = c.post("/login", data={"username": "admin", "password": "admin12345"},
                follow_redirects=False)
    assert r3.status_code == 200, "改密后旧密码应失效"
    # 测完改回，避免污染种子账号
    r = client.post(
        "/admin/change_password",
        data={"old_password": "cross-pass-666", "new_password": "admin12345"},
        follow_redirects=True,
    )
    assert "密码修改成功" in r.text, "恢复默认密码应成功"
    c = TestClient(main.app)
    assert c.post("/login", data={"username": "admin", "password": "admin12345"},
                  follow_redirects=False).status_code == 303, "admin12345 应恢复可登录"


# ---------------------------------------------------------------------------
# 14. 范围核对（NFR-01）
# ---------------------------------------------------------------------------

@checklist(14, "范围核对")
def test_14_scope():
    # 买家页与后台页面无「评价」功能入口
    assert "评价" not in TestClient(main.app).get("/product").text, "买家页不应出现评价功能"
    assert "评价" not in client.get("/admin").text, "后台页不应出现评价功能"
    assert "评价" not in client.get("/admin/history").text, "历史页不应出现评价功能"
    # 路由清单仅 Web：与 main.py 路由表逐一比对，无 App/小程序相关路由
    expected = {
        "/login", "/admin", "/admin/change_password", "/admin/product",
        "/admin/product/freeze", "/admin/product/unfreeze", "/admin/product/mark",
        "/admin/intent/{intent_id}/enter",
        "/admin/history", "/admin/history/{product_id}",
        "/product", "/product/intent", "/cancel",
        "/static",           # 静态资源挂载
        "/openapi.json",     # FastAPI 默认 schema（docs_url/redoc_url 已关闭）
    }
    actual = {getattr(route, "path", "") for route in main.app.routes}
    actual.discard("")
    unexpected = actual - expected
    missing = expected - actual
    assert not unexpected, f"发现预期之外的路由（可能超出仅 Web 范围）: {sorted(unexpected)}"
    assert not missing, f"缺失预期路由: {sorted(missing)}"
    for keyword in ("app", "mini", "android", "ios", "wx"):
        hits = [p for p in actual if keyword in p.lower() and p != "/openapi.json"]
        assert not hits, f"路由中出现疑似 App/小程序关键字「{keyword}」: {hits}"


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

ITEMS = {
    1: "卖家登录", 2: "商品发布", 3: "商品浏览", 4: "提交意向",
    5: "排队规则", 6: "撤销意向", 7: "查看意向人", 8: "冻结",
    9: "解冻", 10: "标记成功", 11: "标记失败", 12: "历史记录",
    13: "修改密码", 14: "范围核对",
}


def main_run() -> int:
    steps = [
        test_01_login, test_02_publish, test_03_browse, test_04_submit_intent,
        test_05_queue_rule, test_06_cancel, test_07_view_intents, test_08_freeze,
        test_09_unfreeze, test_10_mark_success, test_11_mark_fail, test_12_history,
        test_13_change_password, test_14_scope,
    ]
    for fn in steps:
        fn()

    passed = sum(1 for _, ok, _ in _results if ok)
    failed = len(_results) - passed
    print("\n" + "=" * 60)
    print(f"交叉测试结果（对照第 6 章验收清单）：{passed} 通过 / {failed} 失败（共 14 项）")
    if failed:
        print("失败项：")
        for no, ok, msg in _results:
            if not ok:
                print(f"  - 清单{no} {ITEMS[no]}: {msg}")
    print("=" * 60)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main_run())

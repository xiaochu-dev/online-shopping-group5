# -*- coding: utf-8 -*-
"""
test_b_module.py —— 黄程宇模块自测（FR-02 商品发布 / FR-03 浏览与意向提交）
====================================================
覆盖范围（对照验收标准）：
- B-1 发布字段校验：名称缺失、价格缺失/0/负数/非数字拒绝；两位小数正常入库；描述留空成功
- B-2 同一时间仅一件在售，重复发布被拒
- B-3 图片服务端严格校验：伪造后缀拒绝、真 PNG 通过、>5MB 拒绝、0 字节拒绝、
      不传图片使用占位图
- B-4 买家页无需登录可浏览在售商品
- C-1 意向必填校验：姓名/电话缺失拒绝，两项齐备成功
- C-3 同一电话重复提交生成两条独立意向、两个不同口令码

运行：python test_b_module.py
（测试数据库写到临时目录，与 test_smoke.py 的 DB_PATH 环境变量机制保持一致）
"""

import os
import re
import sys
import tempfile

# 数据库写到临时文件，避免污染交付目录；必须在 import db/main 之前设置
_TMP_DB = os.path.join(tempfile.mkdtemp(prefix="shop_mvp_test_b_"), "test_b.db")
os.environ["DB_PATH"] = _TMP_DB

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from db import get_conn  # noqa: E402

client = TestClient(main.app)

PNG_BYTES = main._make_png(2, 2, (0, 128, 255))  # 真 PNG 字节流（IHDR 完整）
PNG_MAGIC = b"\x89\x50\x4e\x47\x0d\x0a\x1a\x0a"

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


def latest_product() -> dict | None:
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT * FROM product ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def reset_products() -> None:
    """清空商品与意向，便于下一段发布测试独立进行。"""
    conn = get_conn()
    try:
        conn.execute("DELETE FROM intent")
        conn.execute("DELETE FROM product")
        conn.commit()
    finally:
        conn.close()


def count_intents(phone: str) -> int:
    conn = get_conn()
    try:
        return conn.execute(
            "SELECT COUNT(*) AS c FROM intent WHERE phone = ?", (phone,)
        ).fetchone()["c"]
    finally:
        conn.close()


def token_of(html: str) -> str:
    m = re.search(r"token-code\">([0-9a-f]{8})<", html)
    assert m, "页面应展示 8 位口令码"
    return m.group(1)


# ---------------------------------------------------------------------------
# 准备：登录卖家后台
# ---------------------------------------------------------------------------

@step("准备：正确账号登录后台")
def t_login():
    r = client.post(
        "/login", data={"username": "admin", "password": "admin12345"},
        follow_redirects=False,
    )
    assert r.status_code == 303, f"登录应成功重定向，实际 {r.status_code}"


# ---------------------------------------------------------------------------
# B-1 发布字段校验
# ---------------------------------------------------------------------------

@step("B-1：名称缺失被拒")
def t_b1_no_name():
    r = client.post("/admin/product", data={"name": "", "price": "9.90"}, follow_redirects=True)
    assert "商品名称不能为空" in r.text, "名称缺失应提示「商品名称不能为空」"
    assert latest_product() is None, "校验失败不应入库"


@step("B-1：价格缺失被拒")
def t_b1_no_price():
    r = client.post("/admin/product", data={"name": "测试商品", "price": ""}, follow_redirects=True)
    assert "价格格式非法" in r.text, "价格缺失应提示「价格格式非法」"
    assert latest_product() is None, "校验失败不应入库"


@step("B-1：价格为 0 被拒")
def t_b1_zero_price():
    r = client.post("/admin/product", data={"name": "测试商品", "price": "0"}, follow_redirects=True)
    assert "价格必须大于 0" in r.text, "价格 0 应提示「价格必须大于 0」"


@step("B-1：价格为负数被拒")
def t_b1_negative_price():
    r = client.post("/admin/product", data={"name": "测试商品", "price": "-3.5"}, follow_redirects=True)
    assert "价格必须大于 0" in r.text, "负数价格应提示「价格必须大于 0」"


@step("B-1：价格为非数字被拒")
def t_b1_nan_price():
    r = client.post("/admin/product", data={"name": "测试商品", "price": "abc"}, follow_redirects=True)
    assert "价格格式非法" in r.text, "非数字价格应提示「价格格式非法」"


@step("B-1：两位小数价格发布成功并正常入库；描述留空成功")
def t_b1_publish_ok():
    r = client.post(
        "/admin/product", data={"name": "单价测试品", "description": "", "price": "12.34"},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, f"发布应成功，实际响应含: {r.text[:200]}"
    row = latest_product()
    assert row is not None and row["name"] == "单价测试品", "商品应已入库"
    assert abs(row["price"] - 12.34) < 1e-9, f"价格应保留两位小数入库为 12.34，实际 {row['price']}"
    assert row["description"] == "", "描述留空应正常入库"
    assert row["status"] == "on_sale", f"发布后应为在售状态，实际 {row['status']}"


# ---------------------------------------------------------------------------
# B-2 同一时间仅一件在售
# ---------------------------------------------------------------------------

@step("B-2：已有一件在售时再发布被拒并提示")
def t_b2_second_rejected():
    r = client.post(
        "/admin/product", data={"name": "第二件", "price": "5.00"}, follow_redirects=True
    )
    assert "同一时间仅允许一件" in r.text, "重复发布应被拒绝并提示"
    row = latest_product()
    assert row["name"] == "单价测试品", "原商品不应被覆盖"
    reset_products()  # 清场，供 B-3 独立测试


# ---------------------------------------------------------------------------
# B-3 图片服务端严格校验（魔数 / 大小 / 空文件 / 占位图）
# ---------------------------------------------------------------------------

@step("B-3：扩展名 .png 但内容是文本的伪造文件被拒（魔数校验）")
def t_b3_fake_png():
    r = client.post(
        "/admin/product",
        data={"name": "伪造图片测试", "price": "1.00"},
        files={"image": ("fake.png", b"this is definitely not a png file", "image/png")},
        follow_redirects=True,
    )
    assert "图片内容与扩展名不符" in r.text, "伪造后缀文件应被魔数校验拒绝"
    assert latest_product() is None, "校验失败不落盘、不入库"


@step("B-3：超过 5MB 的图片被拒")
def t_b3_oversize():
    big = PNG_MAGIC + b"\x00" * (5 * 1024 * 1024)  # 魔数 + 填充，总量 > 5MB
    assert len(big) > 5 * 1024 * 1024
    r = client.post(
        "/admin/product",
        data={"name": "大图测试", "price": "1.00"},
        files={"image": ("big.png", big, "image/png")},
        follow_redirects=True,
    )
    assert "图片大小不能超过 5MB" in r.text, "超 5MB 应明确提示「图片大小不能超过 5MB」"
    assert latest_product() is None, "超限图片不应入库"


@step("B-3：0 字节空文件被拒")
def t_b3_empty_file():
    r = client.post(
        "/admin/product",
        data={"name": "空文件测试", "price": "1.00"},
        files={"image": ("empty.png", b"", "image/png")},
        follow_redirects=True,
    )
    assert "为空" in r.text, "0 字节文件应被拒绝并提示为空"
    assert latest_product() is None, "空文件不应入库"


@step("B-3：真 PNG 字节流上传通过并落盘")
def t_b3_real_png():
    r = client.post(
        "/admin/product",
        data={"name": "真图测试品", "price": "8.88"},
        files={"image": ("real.png", PNG_BYTES, "image/png")},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, f"真 PNG 应上传成功，实际响应含: {r.text[:200]}"
    row = latest_product()
    assert row["image_path"].startswith("uploads/"), f"图片应保存到 uploads/，实际 {row['image_path']}"
    reset_products()  # 清场，供占位图测试


@step("B-3：不传图片发布成功且使用占位图")
def t_b3_placeholder():
    r = client.post(
        "/admin/product", data={"name": "占位图测试商品", "price": "6.66"},
        follow_redirects=True,
    )
    assert "商品发布成功" in r.text, "不传图片应发布成功"
    row = latest_product()
    assert row["image_path"] == "placeholder.png", f"应使用占位图，实际 {row['image_path']}"
    page = client.get("/product")
    assert "/static/placeholder.png" in page.text, "买家页 img src 应指向占位图"


# ---------------------------------------------------------------------------
# B-4 买家页无需登录可浏览
# ---------------------------------------------------------------------------

@step("B-4：未登录 GET /product 返回 200 且包含商品名")
def t_b4_buyer_page_no_login():
    anon = TestClient(main.app)  # 全新无 cookie 会话，模拟未登录买家
    r = anon.get("/product")
    assert r.status_code == 200, f"买家页应 200 可匿名访问，实际 {r.status_code}"
    assert "占位图测试商品" in r.text, "页面应展示在售商品名"


# ---------------------------------------------------------------------------
# C-1 意向必填校验
# ---------------------------------------------------------------------------

@step("C-1：姓名缺失被拒")
def t_c1_no_name():
    r = client.post("/product/intent", data={"buyer_name": "", "phone": "13900000000"})
    assert "姓名与联系电话均为必填项" in r.text, "姓名缺失应提示必填"


@step("C-1：电话缺失被拒")
def t_c1_no_phone():
    r = client.post("/product/intent", data={"buyer_name": "张三", "phone": ""})
    assert "姓名与联系电话均为必填项" in r.text, "电话缺失应提示必填"
    assert count_intents("13900000000") == 0, "校验失败的意向不应入库"


@step("C-1：姓名电话齐备提交成功并展示口令码")
def t_c1_ok():
    r = client.post("/product/intent", data={"buyer_name": "李四", "phone": "13900000001"})
    assert r.status_code == 200
    assert "意向提交成功" in r.text, "齐备信息应提交成功"
    assert "你的口令码" in r.text, "提交成功后应展示口令码"
    assert count_intents("13900000001") == 1, "意向应入库"


# ---------------------------------------------------------------------------
# C-3 同一电话重复提交（不去重、各自独立口令码）
# ---------------------------------------------------------------------------

@step("C-3：同一电话连续提交两次，生成两条独立意向、两个不同口令码")
def t_c3_duplicate_phone():
    phone = "13911112222"
    r1 = client.post("/product/intent", data={"buyer_name": "王五", "phone": phone})
    r2 = client.post("/product/intent", data={"buyer_name": "王五", "phone": phone})
    assert "意向提交成功" in r1.text and "意向提交成功" in r2.text, "同一电话两次提交均应成功（不去重）"
    tok1, tok2 = token_of(r1.text), token_of(r2.text)
    assert tok1 != tok2, f"两次意向的口令码应互不相同，实际均为 {tok1}"
    assert count_intents(phone) == 2, f"同一电话应生成 2 条独立意向记录，实际 {count_intents(phone)}"
    conn = get_conn()
    try:
        rows = conn.execute(
            "SELECT id FROM intent WHERE phone = ?", (phone,)
        ).fetchall()
    finally:
        conn.close()
    assert len({row["id"] for row in rows}) == 2, "两条意向应为独立记录（不同主键）"


# ---------------------------------------------------------------------------
# 清理：删除测试期间上传到 static/uploads 的文件
# ---------------------------------------------------------------------------

def cleanup_uploads() -> None:
    upload_dir = main.UPLOAD_DIR
    if not upload_dir.exists():
        return
    for f in upload_dir.iterdir():
        if f.is_file() and f.name.startswith(("fake", "big", "empty", "real")):
            f.unlink()


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main_run() -> int:
    steps = [
        t_login,
        t_b1_no_name, t_b1_no_price, t_b1_zero_price, t_b1_negative_price,
        t_b1_nan_price, t_b1_publish_ok,
        t_b2_second_rejected,
        t_b3_fake_png, t_b3_oversize, t_b3_empty_file, t_b3_real_png, t_b3_placeholder,
        t_b4_buyer_page_no_login,
        t_c1_no_name, t_c1_no_phone, t_c1_ok,
        t_c3_duplicate_phone,
    ]
    try:
        for fn in steps:
            fn()
    finally:
        cleanup_uploads()

    passed = sum(1 for _, ok, _ in _results if ok)
    failed = len(_results) - passed
    print("\n" + "=" * 60)
    print(f"黄程宇模块自测：{passed} 通过 / {failed} 失败")
    if failed:
        print("失败项：")
        for name, ok, msg in _results:
            if not ok:
                print(f"  - {name}: {msg}")
    print("=" * 60)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main_run())

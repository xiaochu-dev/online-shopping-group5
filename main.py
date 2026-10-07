# -*- coding: utf-8 -*-
"""
main.py —— FastAPI 应用入口 + 全部路由
====================================================
# 公共数据层与状态机骨架 —— 架构设计：胡成溯（组长）

路由分组与署名：
- 后台路由（登录、改密、意向人列表、进入交易、冻结解冻开关、标记结果、历史）
    # 卖家后台模块 —— 胡成溯（组长）：FR-01 / FR-05 / FR-07 / FR-08（Backlog A/E/F(开关)/G/H）
- 商品发布、买家浏览/提交意向路由
    # 商品与意向模块 —— 黄程宇：FR-02 / FR-03（Backlog B/C）
    # ↑ 完善记录（黄程宇，2026-10-07）：图片上传增加服务端严格校验（FR-02-6 细化）——
    #   文件头魔数校验（JPEG: FF D8 FF / PNG: 89 50 4E 47 0D 0A 1A 0A，防伪造后缀）+
    #   扩展名白名单（.jpg/.jpeg/.png）双重校验 + 大小 ≤5MB + 空文件拒绝，校验失败不落盘
- 口令码、队列排序、撤销、递补核心函数（位于 services_state.py）
    # 队列与冻结模块 —— 周到（PM）：FR-04 / FR-06（Backlog D/F）

运行：python main.py → http://127.0.0.1:8000（端口 8000）
"""

import os
import struct
import uuid
import zlib
from pathlib import Path

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.status import HTTP_303_SEE_OTHER

import auth
import services_state as state
from db import get_conn, init_db

BASE_DIR = Path(__file__).resolve().parent
UPLOAD_DIR = BASE_DIR / "static" / "uploads"
PLACEHOLDER_PATH = BASE_DIR / "static" / "placeholder.png"

MAX_IMAGE_BYTES = 5 * 1024 * 1024  # 5MB（FR-02-6）
# 扩展名白名单（FR-02-6）：与文件头魔数双重校验，防伪造后缀
ALLOWED_IMAGE_EXTS = {".jpg", ".jpeg", ".png"}
JPEG_MAGIC = b"\xff\xd8\xff"                       # JPEG 文件头前 3 字节
PNG_MAGIC = b"\x89\x50\x4e\x47\x0d\x0a\x1a\x0a"    # PNG 文件头 8 字节

app = FastAPI(title="在线购物系统 MVP - 第5组", docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))

SESSION_COOKIE = "seller_session"


# ---------------------------------------------------------------------------
# 占位图（FR-02-4：未上传图片时使用占位图）—— 启动时若无则程序化生成一张纯色 PNG
# ---------------------------------------------------------------------------

def _make_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """不依赖第三方库生成纯色 PNG（用于占位图）。"""
    def chunk(tag: bytes, data: bytes) -> bytes:
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(
            ">I", zlib.crc32(c) & 0xFFFFFFFF
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def ensure_placeholder_image() -> None:
    if not PLACEHOLDER_PATH.exists():
        PLACEHOLDER_PATH.write_bytes(_make_png(320, 240, (220, 220, 220)))


ensure_placeholder_image()

# 导入即初始化数据库（幂等）：建表 + 种子卖家账号，保证任何启动方式可用
init_db()


# ---------------------------------------------------------------------------
# 会话保护依赖（FR-01-3：未登录无法访问后台）
# ---------------------------------------------------------------------------

def current_seller(request: Request) -> str | None:
    return auth.verify_session_cookie(request.cookies.get(SESSION_COOKIE))


def require_login(request: Request) -> RedirectResponse:
    """未登录访问后台一律重定向到 /login。"""
    return RedirectResponse("/login", status_code=HTTP_303_SEE_OTHER)


def fmt_price(value: float) -> str:
    return f"{value:.2f}"


templates.env.filters["price"] = fmt_price


# ===========================================================================
# 后台路由 · 登录（FR-01）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）：FR-01 / FR-05 / FR-07 / FR-08（Backlog A/E/F(开关)/G/H）

@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    if current_seller(request):
        return RedirectResponse("/admin", status_code=HTTP_303_SEE_OTHER)
    return templates.TemplateResponse(request, "login.html", {"error": None})


@app.post("/login")
async def login_submit(request: Request, username: str = Form(""), password: str = Form("")):
    """用户名+密码登录（FR-01-1）。成功签发签名 cookie；失败回到登录页提示。"""
    if not username or not password or not auth.check_login(username, password):
        return templates.TemplateResponse(
            request, "login.html",
            {"error": "用户名或密码错误"},
            status_code=200,
        )
    resp = RedirectResponse("/admin", status_code=HTTP_303_SEE_OTHER)
    resp.set_cookie(
        SESSION_COOKIE,
        auth.issue_session_cookie(username),
        httponly=True,
        samesite="lax",
        max_age=7 * 24 * 3600,
    )
    return resp


@app.post("/admin/change_password")
async def change_password(
    request: Request,
    old_password: str = Form(""),
    new_password: str = Form(""),
):
    """修改密码（FR-01-4 / FR-01-5）：先验原密码，新密码 ≥8 位。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    ok, msg = auth.change_password(username, old_password, new_password)
    if ok:
        return RedirectResponse(f"/admin?msg={msg}", status_code=HTTP_303_SEE_OTHER)
    return RedirectResponse(f"/admin?error={msg}", status_code=HTTP_303_SEE_OTHER)


# ===========================================================================
# 商品与意向模块 —— 黄程宇：FR-02 / FR-03（Backlog B/C）
# 后台路由挂载点 · 商品发布
# ===========================================================================

@app.post("/admin/product")
async def publish_product(
    request: Request,
    name: str = Form(""),
    description: str = Form(""),
    price: str = Form(""),
    image: UploadFile | None = File(None),
):
    """发布商品（FR-02）。

    校验规则：
    - 名称必填（FR-02-3）
    - 价格必填、必须 > 0、非法格式拒绝（FR-02-5），保留两位小数
    - 图片可选：JPG/PNG、≤5MB，超限拒绝；未传用占位图（FR-02-4/6）
    - 同一时间在售商品有且仅有一件，已存在则拒绝（FR-02-1/2）
    """
    username = current_seller(request)
    if not username:
        return require_login(request)

    def fail(msg: str) -> RedirectResponse:
        return RedirectResponse(f"/admin?error={msg}", status_code=HTTP_303_SEE_OTHER)

    # 名称必填
    if not name.strip():
        return fail("商品名称不能为空")

    # 价格必填且 > 0
    try:
        price_val = round(float(price), 2)
    except (TypeError, ValueError):
        return fail("价格格式非法，必须为数字")
    if price_val <= 0:
        return fail("价格必须大于 0")

    # 同一时间仅允许一件在售/已冻结商品
    conn = get_conn()
    try:
        if state.get_current_product(conn) is not None:
            return fail("已存在在售/冻结商品，同一时间仅允许一件，请先完成或下架当前商品")

        # 图片校验（可选，FR-02-6 服务端严格校验）：
        # 空文件拒绝 → 大小 ≤5MB → 扩展名白名单 → 文件头魔数（防伪造后缀）；
        # 校验失败不落盘、直接返回 303 回后台并带中文错误提示
        image_path = str(PLACEHOLDER_PATH.name)  # 未上传 → 占位图
        if image is not None and image.filename:
            data = await image.read()
            if not data:
                return fail("上传的图片文件为空，请重新选择图片")
            if len(data) > MAX_IMAGE_BYTES:
                return fail("图片大小不能超过 5MB")
            ext = Path(image.filename).suffix.lower()
            if ext not in ALLOWED_IMAGE_EXTS:
                return fail("图片仅支持 JPG/PNG 格式")
            is_jpeg = data[:3] == JPEG_MAGIC
            is_png = data[:8] == PNG_MAGIC
            if (ext in (".jpg", ".jpeg") and not is_jpeg) or (ext == ".png" and not is_png):
                return fail("图片内容与扩展名不符，仅支持真实的 JPG/PNG 文件")
            UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
            save_name = f"{uuid.uuid4().hex}{ext}"
            (UPLOAD_DIR / save_name).write_bytes(data)
            image_path = f"uploads/{save_name}"

        conn.execute(
            "INSERT INTO product (name, description, image_path, price, status, published_at) "
            "VALUES (?, ?, ?, ?, 'on_sale', ?)",
            (name.strip(), description.strip(), image_path, price_val, state.now_ms()),
        )
        conn.commit()
    finally:
        conn.close()
    return RedirectResponse("/admin?msg=商品发布成功", status_code=HTTP_303_SEE_OTHER)


# ===========================================================================
# 商品与意向模块 —— 黄程宇：FR-02 / FR-03（Backlog B/C）
# 买家页 · 浏览与提交意向
# ===========================================================================

@app.get("/product", response_class=HTMLResponse)
async def buyer_page(request: Request, submitted: str = ""):
    """买家浏览页（FR-03-1/2）：无需登录；冻结时仍可见并提示「商品交易中」；
    无商品时显示「暂无商品在售」。submitted 为提交成功后回显的口令码。"""
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
    finally:
        conn.close()
    return templates.TemplateResponse(
        request, "product.html",
        {"product": product, "token_display": submitted or None, "error": None},
    )


@app.post("/product/intent")
async def submit_intent(request: Request, buyer_name: str = Form(""), phone: str = Form("")):
    """提交购买意向（FR-03-3~8）。

    - 商品「在售」才接收；「已冻结」拒绝（返回「商品交易中」）
    - 姓名、电话均必填；不做格式校验、不去重
    - 成功生成唯一口令码并当场显示
    """
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
        if product is None:
            return templates.TemplateResponse(
                request, "product.html",
                {"product": None, "token_display": None, "error": "暂无商品在售"},
            )
        if product["status"] == "frozen":
            # FR-03-3 / FR-06-4：冻结期不收新意向
            return templates.TemplateResponse(
                request, "product.html",
                {"product": product, "token_display": None,
                 "error": "商品交易中，暂不接受新的购买意向"},
            )
        if not buyer_name.strip() or not phone.strip():
            return templates.TemplateResponse(
                request, "product.html",
                {"product": product, "token_display": None,
                 "error": "姓名与联系电话均为必填项"},
            )
        token = state.create_intent(conn, product["id"], buyer_name.strip(), phone.strip())
        conn.commit()
    finally:
        conn.close()
    # 提交成功：口令码当场显示（FR-03-8）
    return templates.TemplateResponse(
        request, "product.html",
        {"product": product, "token_display": token, "error": None},
    )


# ===========================================================================
# 后台路由 · 意向购买人列表（FR-05）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）：FR-01 / FR-05 / FR-07 / FR-08（Backlog A/E/F(开关)/G/H）

@app.get("/admin", response_class=HTMLResponse)
async def admin_page(request: Request, msg: str = "", error: str = ""):
    """后台主页：当前商品、发布表单、意向购买人列表、冻结/解冻开关、标记结果、改密码。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
        queue = []
        head = None
        trading = False
        if product is not None:
            queue = state.list_queue(conn, product["id"])
            head = state.get_head_intent(conn, product["id"])
            trading = head is not None
    finally:
        conn.close()
    return templates.TemplateResponse(
        request, "admin.html",
        {
            "product": product,
            "queue": queue,
            "head_intent": head,
            "trading": trading,
            "msg": msg,
            "error": error,
        },
    )


# ===========================================================================
# 后台路由 · 进入交易（E-2 / FR-06-1 自动冻结）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）；核心状态流转见 services_state.py
# 队列与冻结模块 —— 周到（PM）：FR-04 / FR-06（Backlog D/F）

@app.post("/admin/intent/{intent_id}/enter")
async def enter_trading(request: Request, intent_id: int):
    """选定队首买家进入线下交易：商品自动冻结，该意向 → 交易中。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    conn = get_conn()
    try:
        ok, msg = state.enter_trading(conn, intent_id)
        conn.commit()
    finally:
        conn.close()
    return RedirectResponse(
        f"/admin?{('msg=' if ok else 'error=')}{msg}", status_code=HTTP_303_SEE_OTHER
    )


# ===========================================================================
# 后台路由 · 冻结/解冻开关（FR-06-2 / FR-06-6）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）；与自动冻结同一状态
# 队列与冻结模块 —— 周到（PM）：FR-06（Backlog F）

@app.post("/admin/product/freeze")
async def manual_freeze(request: Request):
    """手动冻结：on_sale → frozen（与自动冻结同一状态，FR-06-3）。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
        if product is not None:
            state.freeze_product(conn, product["id"])
            conn.commit()
    finally:
        conn.close()
    return RedirectResponse("/admin?msg=商品已冻结（不再接收新意向）", status_code=HTTP_303_SEE_OTHER)


@app.post("/admin/product/unfreeze")
async def manual_unfreeze(request: Request):
    """手动解冻：frozen → on_sale（FR-06-6/7）；交易进行中不允许解冻。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    msg, flag = "当前没有商品", False
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
        if product is not None:
            try:
                state.unfreeze_product(conn, product["id"])
                conn.commit()
                msg, flag = "商品已解冻，恢复在售", True
            except ValueError as e:
                conn.rollback()
                msg, flag = str(e), False
    finally:
        conn.close()
    return RedirectResponse(
        f"/admin?{'msg' if flag else 'error'}={msg}", status_code=HTTP_303_SEE_OTHER
    )


# ===========================================================================
# 后台路由 · 标记交易结果（FR-07）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）：FR-07；递补逻辑见 services_state.py
# 队列与冻结模块 —— 周到（PM）：FR-04（Backlog D）

@app.post("/admin/product/mark")
async def mark_result(request: Request, result: str = Form("")):
    """标记结果：仅卖家可标记（FR-07-1）。

    - success：商品下架入历史，队首成功，其余排队意向转失败
    - fail：队首失败，商品按递补规则处理（有人则自动递补冻结，无人则恢复在售）
    """
    username = current_seller(request)
    if not username:
        return require_login(request)
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
        if product is None:
            return RedirectResponse("/admin?error=当前没有商品", status_code=HTTP_303_SEE_OTHER)
        if result not in ("success", "fail"):
            return RedirectResponse("/admin?error=无效的结果标记", status_code=HTTP_303_SEE_OTHER)
        ok, msg = state.mark_result(conn, product["id"], success=(result == "success"))
        conn.commit()
    finally:
        conn.close()
    if ok and result == "success":
        return RedirectResponse("/admin?msg=" + msg, status_code=HTTP_303_SEE_OTHER)
    return RedirectResponse(f"/admin?{'msg' if ok else 'error'}={msg}", status_code=HTTP_303_SEE_OTHER)


# ===========================================================================
# 后台路由 · 历史商品记录（FR-08）
# ===========================================================================
# 卖家后台模块 —— 胡成溯（组长）：FR-08（Backlog G/H）

HISTORY_PAGE_SIZE = 5  # 每页 5 条


@app.get("/admin/history", response_class=HTMLResponse)
async def history_list(request: Request, page: int = 1):
    """历史商品列表：按交易时间倒序、分页（每页 5 条）、页码导航（FR-08-1/2/3）。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    page = max(page, 1)
    conn = get_conn()
    try:
        total = conn.execute(
            "SELECT COUNT(*) AS c FROM product WHERE status = 'delisted'"
        ).fetchone()["c"]
        total_pages = max((total + HISTORY_PAGE_SIZE - 1) // HISTORY_PAGE_SIZE, 1)
        page = min(page, total_pages)
        items = conn.execute(
            "SELECT * FROM product WHERE status = 'delisted' "
            "ORDER BY traded_at DESC, id DESC LIMIT ? OFFSET ?",
            (HISTORY_PAGE_SIZE, (page - 1) * HISTORY_PAGE_SIZE),
        ).fetchall()
    finally:
        conn.close()
    return templates.TemplateResponse(
        request, "history.html",
        {
            "items": items, "page": page, "total_pages": total_pages,
            "total": total, "has_prev": page > 1, "has_next": page < total_pages,
        },
    )


@app.get("/admin/history/{product_id}", response_class=HTMLResponse)
async def history_detail(request: Request, product_id: int):
    """历史商品详情：该商品全部意向（姓名、电话、提交时间、交易结果），只读（FR-08-4/5/6）。"""
    username = current_seller(request)
    if not username:
        return require_login(request)
    conn = get_conn()
    try:
        product = state.get_product_by_id(conn, product_id)
        intents = []
        if product is not None:
            intents = conn.execute(
                "SELECT * FROM intent WHERE product_id = ? ORDER BY created_at ASC, id ASC",
                (product_id,),
            ).fetchall()
    finally:
        conn.close()
    if product is None:
        return RedirectResponse("/admin/history", status_code=HTTP_303_SEE_OTHER)
    return templates.TemplateResponse(
        request, "history_detail.html", {"product": product, "intents": intents}
    )


# ===========================================================================
# 口令码撤销（D-3 / FR-04-4~6）
# ===========================================================================
# 队列与冻结模块 —— 周到（PM）：FR-04（Backlog D）；入口挂载于买家页

@app.post("/cancel")
async def cancel_intent(request: Request, token_code: str = Form("")):
    """买家凭口令码撤销 pending 意向；撤销后队列位次自动前移。"""
    conn = get_conn()
    try:
        row = state.cancel_intent(conn, token_code.strip())
        conn.commit()
    finally:
        conn.close()
    conn = get_conn()
    try:
        product = state.get_current_product(conn)
    finally:
        conn.close()
    if row is not None:
        return templates.TemplateResponse(
            request, "product.html",
            {"product": product, "token_display": None,
             "notice": f"口令码 {row['token_code']} 对应的意向已撤销"},
        )
    return templates.TemplateResponse(
        request, "product.html",
        {"product": product, "token_display": None,
         "error": "口令码无效，或该意向不可撤销（仅排队中的意向可撤销）"},
    )


# ---------------------------------------------------------------------------
# 入口：端口 8000
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    init_db()
    ensure_placeholder_image()
    uvicorn.run(app, host="0.0.0.0", port=8000)

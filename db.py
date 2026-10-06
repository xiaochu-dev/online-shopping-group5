# -*- coding: utf-8 -*-
"""
db.py —— sqlite3 数据层
====================================================
# 公共数据层与状态机骨架 —— 架构设计：胡成溯（组长）

职责：
- sqlite3 连接管理（Row 工厂、WAL、外键）
- 三张表的建表 DDL：seller / product / intent
- 种子卖家账号（唯一账号 admin / admin12345，sha256+salt）

数据库文件路径：
- 默认写到系统临时目录（避免在交付目录留下 .db 文件）
- 可用环境变量 DB_PATH 覆盖；测试代码也会显式传入临时文件路径
"""

import os
import secrets
import sqlite3
import tempfile

# ---------------------------------------------------------------------------
# 路径与连接
# ---------------------------------------------------------------------------

# 数据库文件默认路径：系统临时目录 / online_shop_mvp.db（支持 DB_PATH 环境变量覆盖）
_DEFAULT_DB_PATH = os.path.join(tempfile.gettempdir(), "online_shop_mvp.db")


def get_db_path() -> str:
    """返回数据库文件路径（支持环境变量 DB_PATH 覆盖）。"""
    return os.environ.get("DB_PATH") or _DEFAULT_DB_PATH


def get_conn(db_path: str | None = None) -> sqlite3.Connection:
    """获取 sqlite3 连接。

    - row_factory = sqlite3.Row：查询结果可按列名取值
    - 每次调用独立连接，调用方自行 close（本项目请求量小，简单可靠）
    """
    path = db_path or get_db_path()
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


# ---------------------------------------------------------------------------
# DDL：三张表
# ---------------------------------------------------------------------------

DDL = """
CREATE TABLE IF NOT EXISTS seller (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    salt          TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS product (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT NOT NULL,
    description  TEXT,
    image_path   TEXT,
    price        REAL NOT NULL CHECK (price > 0),
    status       TEXT NOT NULL DEFAULT 'on_sale'
                 CHECK (status IN ('on_sale', 'frozen', 'delisted')),
    published_at TEXT NOT NULL,
    traded_at    TEXT,
    final_result TEXT
);

CREATE TABLE IF NOT EXISTS intent (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES product(id),
    buyer_name TEXT NOT NULL,
    phone      TEXT NOT NULL,
    token_code TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'pending'
               CHECK (status IN ('pending', 'trading', 'success', 'failed', 'cancelled')),
    created_at TEXT NOT NULL
);
"""


# ---------------------------------------------------------------------------
# 初始化与种子数据
# ---------------------------------------------------------------------------

def init_db(db_path: str | None = None) -> None:
    """建表 + 写入种子卖家账号（幂等：重复调用无副作用）。"""
    conn = get_conn(db_path)
    try:
        conn.executescript(DDL)
        # 种子卖家：系统内有且仅有一个账号 admin / admin12345（FR-01-1 / FR-01-2）
        row = conn.execute("SELECT id FROM seller WHERE username = ?", ("admin",)).fetchone()
        if row is None:
            _insert_seed_seller(conn)
        conn.commit()
    finally:
        conn.close()


def _insert_seed_seller(conn: sqlite3.Connection) -> None:
    """写入默认卖家账号 admin/admin12345（sha256+salt 存储，不明文）。"""
    # 延迟导入避免 auth.db 与 db 循环依赖
    from auth import hash_password
    salt = secrets.token_hex(8)
    pwd_hash = hash_password("admin12345", salt)
    conn.execute(
        "INSERT INTO seller (username, password_hash, salt) VALUES (?, ?, ?)",
        ("admin", pwd_hash, salt),
    )


# 直接运行本文件时初始化一次，方便手工检查
if __name__ == "__main__":
    init_db()
    print(f"数据库已初始化：{get_db_path()}")

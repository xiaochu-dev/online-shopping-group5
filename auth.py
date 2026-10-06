# -*- coding: utf-8 -*-
"""
auth.py —— 卖家登录 / 会话 / 改密码
====================================================
# 卖家后台模块 —— 胡成溯（组长）：FR-01 / FR-05 / FR-07 / FR-08（Backlog A/E/F(开关)/G/H）

- 密码：sha256(salt + password) 十六进制存储，不明文（FR-01）
- 会话：HMAC-SHA256 签名 cookie（itsdangerous），内容仅含用户名与时间戳
- 本版本不做退出登录（FR-01-6），cookie 有效期 7 天
"""

import hashlib
import secrets

from itsdangerous import BadSignature, TimestampSigner

from db import get_conn

# ---------------------------------------------------------------------------
# 密码哈希
# ---------------------------------------------------------------------------

def hash_password(password: str, salt: str) -> str:
    """sha256(salt + password)，返回十六进制哈希。"""
    return hashlib.sha256((salt + password).encode("utf-8")).hexdigest()


def verify_password(password: str, salt: str, password_hash: str) -> bool:
    """常量时间比较，防时序侧信道。"""
    return secrets.compare_digest(hash_password(password, salt), password_hash)


# ---------------------------------------------------------------------------
# 登录校验
# ---------------------------------------------------------------------------

def check_login(username: str, password: str) -> bool:
    """用户名+密码校验（FR-01-1）。账号不存在或密码错误一律返回 False。"""
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT password_hash, salt FROM seller WHERE username = ?", (username,)
        ).fetchone()
    finally:
        conn.close()
    if row is None:
        return False
    return verify_password(password, row["salt"], row["password_hash"])


# ---------------------------------------------------------------------------
# 会话 cookie（HMAC 签名）
# ---------------------------------------------------------------------------

_SECRET_KEY = secrets.token_hex(32)  # 进程内随机密钥，重启后会话失效，可接受
_SESSION_TTL_SECONDS = 7 * 24 * 3600


def issue_session_cookie(username: str) -> str:
    """签发签名会话 cookie 值：'username:签发时间戳:签名'，带有效期。"""
    signer = TimestampSigner(_SECRET_KEY, salt="seller-session")
    return signer.sign(username.encode("utf-8")).decode("ascii")


def verify_session_cookie(cookie_value: str | None) -> str | None:
    """校验会话 cookie：签名正确且未过期返回用户名，否则 None。"""
    if not cookie_value:
        return None
    signer = TimestampSigner(_SECRET_KEY, salt="seller-session")
    try:
        payload = signer.unsign(
            cookie_value.encode("ascii"), max_age=_SESSION_TTL_SECONDS
        )
        return payload.decode("utf-8")
    except BadSignature:
        return None


# ---------------------------------------------------------------------------
# 修改密码
# ---------------------------------------------------------------------------

def change_password(username: str, old_password: str, new_password: str) -> tuple[bool, str]:
    """修改密码（FR-01-4 / FR-01-5）。

    规则：
    1. 必须校验原密码，不通过则拒绝
    2. 新密码长度不少于 8 位
    3. 旧 salt 一并轮换
    """
    conn = get_conn()
    try:
        row = conn.execute(
            "SELECT password_hash, salt FROM seller WHERE username = ?", (username,)
        ).fetchone()
        if row is None:
            return False, "账号不存在"
        if not verify_password(old_password, row["salt"], row["password_hash"]):
            return False, "原密码错误"
        if len(new_password) < 8:
            return False, "新密码长度不得少于 8 位"
        new_salt = secrets.token_hex(8)
        conn.execute(
            "UPDATE seller SET password_hash = ?, salt = ? WHERE username = ?",
            (hash_password(new_password, new_salt), new_salt, username),
        )
        conn.commit()
        return True, "密码修改成功"
    finally:
        conn.close()

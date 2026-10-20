-- ==========================================================================
-- 01-buyer.sql　买家账号（owner：胡成溯　→ 挑战需求 A.2.1）
-- --------------------------------------------------------------------------
-- 表结构按《挑战需求A-澄清问答记录》定稿：
--   · 用户名唯一
--   · 密码：sha256(salt + 明文)，8 字节盐，与卖家账号算法完全一致（不引入 BCrypt）
--   · 电话：必填，但【不做唯一约束、不做格式强校验】——澄清结论明确「电话可重复填」
--   · 默认交易地点：选填
-- 本表只由 buyer / admin 模块读写，其他模块不得直接查询。
-- ==========================================================================

CREATE TABLE IF NOT EXISTS buyer
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,   -- 登录名，唯一
    password_hash TEXT    NOT NULL,          -- sha256(salt + 明文密码) 的十六进制
    salt          TEXT    NOT NULL,          -- 8 字节随机盐的十六进制，改密时轮换
    phone         TEXT    NOT NULL,          -- 联系电话：必填、不唯一、不校验格式
    default_place TEXT,                      -- 默认交易地点：选填
    created_at    INTEGER NOT NULL           -- 注册时间，毫秒时间戳
);

-- 卖家后台「客户管理」按注册时间倒序分页，走此索引
CREATE INDEX IF NOT EXISTS idx_buyer_created ON buyer (created_at);

-- 后台按电话检索（phone 不唯一，索引只加速前缀/等值查询）
CREATE INDEX IF NOT EXISTS idx_buyer_phone ON buyer (phone);

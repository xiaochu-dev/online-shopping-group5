-- ==========================================================================
-- 00-base.sql　基础层（owner：胡成溯）
-- --------------------------------------------------------------------------
-- 本文件只放「全系统共用、不属于任何单一业务模块」的表与种子数据。
-- 迭代 2 起建表脚本按模块拆分，加载顺序由 application.yml 的
-- spring.sql.init.schema-locations 控制：00 → 01 → 02 → 03。
-- 执行策略：随应用启动自动执行，全部 IF NOT EXISTS / INSERT OR IGNORE，可重复执行。
-- ==========================================================================

-- WAL 模式：读写不互相阻塞；该设置持久化到数据库文件，只需设置一次
PRAGMA journal_mode = WAL;

-- --------------------------------------------------------------------------
-- seller 卖家账号（全系统仅一条，迭代 1 遗留，不归任何业务模块）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,          -- sha256(salt + 明文密码) 的十六进制
    salt          TEXT    NOT NULL,          -- 8 字节随机盐的十六进制，改密时轮换
    created_at    INTEGER NOT NULL           -- 创建时间，毫秒时间戳
);

-- 默认卖家账号：admin / admin12345（首次登录后请立即改密）
INSERT OR IGNORE INTO seller (id, username, password_hash, salt, created_at)
VALUES (1,
        'admin',
        '83c68c8188a769d8d9dbc38a2e479bb3f739c71de2b0f9c3c6d5865c0795562e',
        '9f3c1a7b5e2d4086',
        CAST(strftime('%s', 'now') AS INTEGER) * 1000);

-- ==========================================================================
-- 第 5 组 在线购物系统 · 迭代 2 建表脚本（SQLite）
-- 负责人：胡成溯（组长）T2-03
-- --------------------------------------------------------------------------
-- 执行策略：随应用启动自动执行（spring.sql.init.mode = always）。
-- 全部语句均为 CREATE ... IF NOT EXISTS / INSERT OR IGNORE，
-- 因此【可重复执行】，重启应用不会报错、不会重复插入数据。
--
-- 相对迭代 1 的变更（详见 docs/数据库设计-迭代2.md）：
--   1. product 新增 seller_id、category_id、updated_at，支持「多商品 + 卖家隔离」
--   2. 删除「同一时间仅允许一件在售」的业务约束（改为多商品）
--   3. 新增 category 分类字典表
--   4. intent 的口令码字段统一命名为 token_code（迭代 1 为 passcode）
-- ==========================================================================

-- WAL 模式：读写不互相阻塞，且该设置会持久化到数据库文件，只需设置一次
PRAGMA journal_mode = WAL;

-- --------------------------------------------------------------------------
-- 表 1：seller 卖家账号（全系统仅一条记录）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seller
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,          -- sha256(salt + 明文密码) 的十六进制
    salt          TEXT    NOT NULL,          -- 8 字节随机盐的十六进制，改密时轮换
    created_at    INTEGER NOT NULL           -- 创建时间，毫秒时间戳
);

-- --------------------------------------------------------------------------
-- 表 2：category 商品分类字典（迭代 2 新增）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS category
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL UNIQUE,
    sort_no    INTEGER NOT NULL DEFAULT 0,   -- 下拉框排序，越小越靠前
    status     TEXT    NOT NULL DEFAULT 'enabled'
        CHECK (status IN ('enabled', 'disabled')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- --------------------------------------------------------------------------
-- 表 3：product 商品（迭代 2 多商品化）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_id    INTEGER NOT NULL REFERENCES seller (id),   -- 商品归属卖家，用于「我的商品」
    category_id  INTEGER          REFERENCES category (id), -- 可为空：未分类商品
    name         TEXT    NOT NULL,
    description  TEXT    NOT NULL DEFAULT '',
    image_path   TEXT    NOT NULL DEFAULT 'images/placeholder.png',
    price_cents  INTEGER NOT NULL CHECK (price_cents > 0),  -- 以「分」存整数，规避浮点误差
    -- 商品状态：on_sale 在售 / delisted 已下架 / frozen 交易中冻结
    -- 迭代 2 只用到 on_sale 与 delisted；frozen 预留给迭代 3 的队列与冻结功能，
    -- 提前写入 CHECK 约束可避免迭代 3 再做表迁移。
    status       TEXT    NOT NULL DEFAULT 'on_sale'
        CHECK (status IN ('on_sale', 'delisted', 'frozen')),
    published_at INTEGER NOT NULL,                          -- 发布时间，毫秒时间戳
    updated_at   INTEGER NOT NULL,                          -- 最近修改时间，毫秒时间戳
    traded_at    INTEGER,                                   -- 下架/成交时间，毫秒时间戳
    trade_result TEXT
        CHECK (trade_result IN ('success', 'failed', 'manual_delist'))
);

-- --------------------------------------------------------------------------
-- 表 4：intent 购买意向（提交即入队，凭口令码撤销）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intent
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES product (id),
    buyer_name TEXT    NOT NULL,
    phone      TEXT    NOT NULL,           -- 不校验格式、不按号码去重（同一号码可多次提交）
    token_code TEXT    NOT NULL UNIQUE,    -- 32 位随机十六进制，凭码撤销
    status     TEXT    NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'trading', 'success', 'failed', 'cancelled')),
    created_at INTEGER NOT NULL            -- 提交时间；队列主排序键
);

-- --------------------------------------------------------------------------
-- 索引：与三人模块的查询路径一一对应
-- --------------------------------------------------------------------------
-- 「我的商品」：WHERE seller_id = ? AND status = ?
CREATE INDEX IF NOT EXISTS idx_product_seller ON product (seller_id, status);
-- 买家列表：WHERE status = 'on_sale' ORDER BY published_at DESC
CREATE INDEX IF NOT EXISTS idx_product_status ON product (status, published_at);
-- 分类筛选：WHERE category_id = ? AND status = 'on_sale'
CREATE INDEX IF NOT EXISTS idx_product_category ON product (category_id, status);
-- 队列：WHERE product_id = ? AND status = 'pending' ORDER BY created_at, id
CREATE INDEX IF NOT EXISTS idx_intent_queue ON intent (product_id, status, created_at);
-- 按号码查历史提交（后台核对用）
CREATE INDEX IF NOT EXISTS idx_intent_phone ON intent (phone);

-- --------------------------------------------------------------------------
-- 种子数据（可重复执行）
-- --------------------------------------------------------------------------

-- 默认卖家账号：admin / admin12345
-- salt         = 9f3c1a7b5e2d4086（16 位十六进制，8 字节）
-- password_hash = sha256('9f3c1a7b5e2d4086' + 'admin12345')
-- ⚠️ 首次登录后请立即在「修改密码」处改掉，否则演示时任何人都能登进后台。
INSERT OR IGNORE INTO seller (id, username, password_hash, salt, created_at)
VALUES (1,
        'admin',
        '83c68c8188a769d8d9dbc38a2e479bb3f739c71de2b0f9c3c6d5865c0795562e',
        '9f3c1a7b5e2d4086',
        CAST(strftime('%s', 'now') AS INTEGER) * 1000);

-- 默认分类字典：黄程宇的发布页下拉框直接可用
INSERT OR IGNORE INTO category (id, name, sort_no, status, created_at, updated_at)
VALUES (1, '数码电子', 1, 'enabled', CAST(strftime('%s', 'now') AS INTEGER) * 1000,
        CAST(strftime('%s', 'now') AS INTEGER) * 1000),
       (2, '图书教材', 2, 'enabled', CAST(strftime('%s', 'now') AS INTEGER) * 1000,
        CAST(strftime('%s', 'now') AS INTEGER) * 1000),
       (3, '服饰鞋包', 3, 'enabled', CAST(strftime('%s', 'now') AS INTEGER) * 1000,
        CAST(strftime('%s', 'now') AS INTEGER) * 1000),
       (4, '生活百货', 4, 'enabled', CAST(strftime('%s', 'now') AS INTEGER) * 1000,
        CAST(strftime('%s', 'now') AS INTEGER) * 1000),
       (5, '运动户外', 5, 'enabled', CAST(strftime('%s', 'now') AS INTEGER) * 1000,
        CAST(strftime('%s', 'now') AS INTEGER) * 1000);

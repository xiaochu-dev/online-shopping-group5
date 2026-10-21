-- ==========================================================================
-- 02-product.sql　商品中心（owner：黄程宇　→ 挑战需求 A.1.1 / A.1.2 / A.1.3 / A.1.5）
-- --------------------------------------------------------------------------
-- 本文件由黄程宇维护。胡成溯在 T2-01 拆分脚本时只搬运了既有表结构，
-- 未改动任何字段；下列 TODO 为本迭代待黄程宇补齐的变更（见任务表 v4.0 第五节）：
--   TODO A.1.2：category 增加 parent_id，并把 name 的全表 UNIQUE 改为 UNIQUE(parent_id, name)
--   TODO A.1.3：product 增加 stock(≥0)、off_reason(auto|manual)
--   TODO A.1.5：新建 product_image(id, product_id, file_path, sort_no, is_primary)
-- 注意：SQLite 不支持删约束，改 UNIQUE 需重建表（备份 shop.db → 重建 → 重灌种子）。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- category 商品分类字典（A.1.2 两级分类，parent_id 待黄程宇补）
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
-- product 商品（A.1.1 多商品；stock / off_reason 待黄程宇补）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS product
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_id    INTEGER NOT NULL REFERENCES seller (id),   -- 商品归属卖家，用于「我的商品」
    category_id  INTEGER          REFERENCES category (id), -- A.1.2 定稿后改为必填且到二级
    name         TEXT    NOT NULL,
    description  TEXT    NOT NULL DEFAULT '',
    image_path   TEXT    NOT NULL DEFAULT 'images/placeholder.png', -- 主图，product_image 落地后作兼容字段
    price_cents  INTEGER NOT NULL CHECK (price_cents > 0),  -- 以「分」存整数，规避浮点误差
    -- 商品状态：on_sale 在售 / delisted 已下架 / frozen 交易中冻结
    status       TEXT    NOT NULL DEFAULT 'on_sale'
        CHECK (status IN ('on_sale', 'delisted', 'frozen')),
    published_at INTEGER NOT NULL,                          -- 发布时间，毫秒时间戳
    updated_at   INTEGER NOT NULL,                          -- 最近修改时间，毫秒时间戳
    traded_at    INTEGER,                                   -- 下架/成交时间，毫秒时间戳
    trade_result TEXT
        CHECK (trade_result IN ('success', 'failed', 'manual_delist'))
);

-- 「我的商品」：WHERE seller_id = ? AND status = ?
CREATE INDEX IF NOT EXISTS idx_product_seller ON product (seller_id, status);
-- 买家列表：WHERE status = 'on_sale' ORDER BY published_at DESC
CREATE INDEX IF NOT EXISTS idx_product_status ON product (status, published_at);
-- 分类筛选：WHERE category_id = ? AND status = 'on_sale'
CREATE INDEX IF NOT EXISTS idx_product_category ON product (category_id, status);

-- 默认分类字典（A.1.2 改造后这些一级分类下再挂二级）
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

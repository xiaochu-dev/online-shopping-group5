-- ==========================================================================
-- 05-cart.sql　购物车（owner：胡成溯　→ 挑战需求 B.2.2 / B.2.3 / B.2.4）
-- --------------------------------------------------------------------------
-- 本文件由胡成溯维护。挑战需求 B 原文：
--   B.2.2 客户可以选择商品进入购物车
--   B.2.3 客户可以从购物车中选择多件商品进行下单
--   B.2.4 客户可以从购物车中选择多件商品转为收藏
--
-- 设计取舍（为什么存快照字段）：
--   购物车页面要显示「商品名 + 单价」，若每次都跨模块读 product 表，购物车域
--   就和商品域耦合了（违反「一人一模块、互不交叉」）。这里沿用 intent 表
--   buyer_name / trade_address 的既有做法：**加购时把名称与单价写成快照**，
--   页面渲染只读本表。商品改价后购物车显示旧价，下单时由订单域按当下价格复核，
--   这是电商里「购物车价格仅供参考」的常规口径。
--
-- 为什么用 (buyer_id, product_id) 唯一索引：
--   同一买家对同一商品的多次加购应合并成一行并累加数量，而不是堆出多行；
--   唯一索引让「合并」有库层兜底，服务层用「先查后改」实现累加，并发下由
--   该索引兜底（数据源连接池固定为 1，写事务天然串行）。
--
-- 数量上限 99：防止误输入把库存校验压力放大，也避免页面出现 5 位数。
-- ==========================================================================

CREATE TABLE IF NOT EXISTS cart
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 逻辑外键 → buyer(id)，同 intent.buyer_id 的处理：不写 REFERENCES，
    -- 避免 01-buyer 与 05-cart 的装载顺序互相牵制
    buyer_id     INTEGER NOT NULL,
    product_id   INTEGER NOT NULL REFERENCES product (id),
    quantity     INTEGER NOT NULL DEFAULT 1 CHECK (quantity >= 1 AND quantity <= 99),
    product_name TEXT    NOT NULL DEFAULT '',   -- 加购时的商品名快照
    price_cents  INTEGER NOT NULL DEFAULT 0 CHECK (price_cents >= 0), -- 加购时单价快照（单位：分）
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

-- 同一买家同一商品只有一行，重复加购走「累加数量」
CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_buyer_product ON cart (buyer_id, product_id);

-- 购物车列表按「最近改动」倒序，索引匹配 (buyer_id, updated_at)
CREATE INDEX IF NOT EXISTS idx_cart_buyer ON cart (buyer_id, updated_at);

-- ==========================================================================
-- 03-intent.sql　购买意向与交易（owner：周到　→ 挑战需求 A.1.3 / A.2.2 / A.2.5）
-- --------------------------------------------------------------------------
-- 本文件由周到维护。胡成溯在 T2-01 拆分脚本时只搬运了既有表结构，未改动字段；
-- 下列 TODO 已在本版全部落地（对应任务表 v4.0 第五节）：
--   ✔ A.2.1：新增 buyer_id NOT NULL（下单必须登录），加索引 (buyer_id, created_at)
--   ✔ 一次意向 = 1 件，不设 quantity 字段（「一次买 3 件」应拆成 3 笔交易）
--   ✔ 新增 finished_at（交易时间）、trade_result（success|failed）、
--        fail_reason（失败原因）、trade_address（下单时的地点快照）
--   ✔ token_code 改为可空：主路径不再生成，基线成果物与存量数据仍可凭码撤销，迭代 3 移除
--
-- 为什么单据级要有 finished_at / trade_result：
--   商品级的 product.traded_at 只能记「最后一次」，而库存 N 件时同一商品会成交多次，
--   「我的订单」要按【这一单】的交易时间排序与展示，只能落在 intent 上。
--
-- 存量匿名意向：按《挑战需求A-澄清问答记录》不并入任何客户，buyer_id 落 0；
-- 客户历史只查 buyer_id = ?，0 永远查不到，不会串到任何人账下。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- intent 购买意向（提交即入队；status 由卖家在后台推进）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intent
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id    INTEGER NOT NULL REFERENCES product (id),
    -- 逻辑外键 → buyer(id)。不写 REFERENCES，避免 01-buyer 与 03-intent 的装载顺序互相牵制
    buyer_id      INTEGER NOT NULL DEFAULT 0,     -- 0 = 迭代 2 基线遗留的匿名单
    buyer_name    TEXT    NOT NULL DEFAULT '',    -- 下单时的姓名快照
    phone         TEXT    NOT NULL DEFAULT '',    -- 下单时的电话快照，不校验格式
    trade_address TEXT    NOT NULL DEFAULT '',    -- 下单时的交易地点快照
    token_code    TEXT,                           -- 基线兜底，新单为 NULL
    -- 状态机：pending → trading → success / failed
    -- 「取消」不是独立状态，卖家的「取消」就等于把单据置 failed
    status        TEXT    NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'trading', 'success', 'failed', 'cancelled')),
    trade_result  TEXT CHECK (trade_result IN ('success', 'failed')),
    fail_reason   TEXT,
    created_at    INTEGER NOT NULL,               -- 下单时间；队列主排序键
    finished_at   INTEGER                         -- 交易时间；我的订单排序键
);

-- --------------------------------------------------------------------------
-- 索引：与查询路径一一对应
-- --------------------------------------------------------------------------
-- 队列：WHERE product_id = ? AND status = 'pending' ORDER BY created_at, id
CREATE INDEX IF NOT EXISTS idx_intent_queue ON intent (product_id, status, created_at);
-- 我的订单：WHERE buyer_id = ? AND status IN ('success','failed') ORDER BY finished_at DESC
CREATE INDEX IF NOT EXISTS idx_intent_buyer ON intent (buyer_id, status, finished_at);
-- 客户详情页的购买历史：WHERE buyer_id = ? ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_intent_buyer_id ON intent (buyer_id, created_at);
-- 按号码查历史提交（后台核对用）
CREATE INDEX IF NOT EXISTS idx_intent_phone ON intent (phone);

-- 口令码唯一：基线遗留，新单为 NULL；NULL 不参与唯一比较
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_token ON intent (token_code)
    WHERE token_code IS NOT NULL;

-- 「同一买家 + 同一商品，同时最多 1 条未结束意向」
-- 用部分唯一索引在库层兜底，防止并发下应用层查重被绕过
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_buyer_active ON intent (buyer_id, product_id)
    WHERE status IN ('pending', 'trading') AND buyer_id > 0;

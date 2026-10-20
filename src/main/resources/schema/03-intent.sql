-- ==========================================================================
-- 03-intent.sql　购买意向与交易（owner：周到　→ 挑战需求 A.1.3 / A.2.2 / A.2.5）
-- --------------------------------------------------------------------------
-- 本文件由周到维护。胡成溯在 T2-01 拆分脚本时只搬运了既有表结构，未改动字段。
-- 下列 TODO 为本迭代待周到补齐的变更（对应任务表 v4.0 第五节）：
--   TODO A.2.1：buyer_id 改为 NOT NULL（下单必须登录），并加索引 (buyer_id, created_at)
--   TODO 删除 quantity（一次意向 = 1 件，v4.0 定稿）
--   TODO 新增 finished_at（交易时间）、trade_result（success|failed）、
--        fail_reason（失败原因）、trade_address（下单时的地点快照）
--   TODO token_code 保留可空（基线成果物需要，主路径不再生成，迭代 3 移除）
-- ==========================================================================

-- --------------------------------------------------------------------------
-- intent 购买意向（提交即入队；status 由卖家在后台推进）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intent
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES product (id),
    buyer_name TEXT    NOT NULL,
    phone      TEXT    NOT NULL,           -- 不校验格式、不按号码去重
    token_code TEXT    UNIQUE,             -- 迭代 2 起不再生成；保留以兼容基线数据
    status     TEXT    NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'trading', 'success', 'failed', 'cancelled')),
    created_at INTEGER NOT NULL            -- 提交时间；队列主排序键
);

-- 队列：WHERE product_id = ? AND status = 'pending' ORDER BY created_at, id
CREATE INDEX IF NOT EXISTS idx_intent_queue ON intent (product_id, status, created_at);
-- 按号码查历史提交（后台核对用）
CREATE INDEX IF NOT EXISTS idx_intent_phone ON intent (phone);

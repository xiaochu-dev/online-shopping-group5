-- ==========================================================================
-- 第 5 组 在线购物系统 · 迭代 2（挑战需求 A）· 表 03：intent 购买意向
-- owner：周到（T2-16 / T2-17 / T2-18）——本表的数据写入权只在本模块
-- --------------------------------------------------------------------------
-- 相对迭代 2 基线的变更（对应《挑战需求A-澄清问答记录》）：
--   1. 新增 buyer_id（NOT NULL）—— A.2.1「下单必须登录」，身份即账号
--   2. 新增 trade_address —— 下单时快照客户默认交易地点（允许当次改写）
--   3. 新增 finished_at / trade_result / fail_reason —— A.2.2「历史下单」
--      只显示有结果的单，需要单据级的交易时间与结果（商品级字段装不下）
--   4. token_code 由 NOT NULL 改为可空 —— 主路径不再生成（基线验收物仍可凭码撤销）
--   5. 删除 quantity —— 一次交易恒为 1 件，字段留着必然被误用成「一次买 3 件」
--
-- 为什么整张表重建而不是 ALTER TABLE：
--   SQLite 不支持「删除 NOT NULL 约束」，token_code 从必填改成可空只能重建表；
--   顺带把新增列一次性加齐，避免多次 ALTER 拼出多种库结构。
--   重建脚本由 IntentSchemaMigrator 在「intent.buyer_id 不存在」时执行一次，
--   已迁移过的库直接跳过，因此本文件不需要（也做不到）写成可重复执行。
--
-- 存量匿名意向：按澄清结论「不并入任何客户」，buyer_id 统一落 0；
-- 客户历史只查 buyer_id = ? 且 status IN ('success','failed')，0 永远不会被查到。
-- ==========================================================================

CREATE TABLE intent__new
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id    INTEGER NOT NULL REFERENCES product (id),
    -- 逻辑外键 → buyer(id)，由账号与客户模块（胡成溯 T2-02）维护；
    -- 这里不写 REFERENCES，避免 01-buyer.sql 与 03-intent.sql 的装载顺序互相牵制
    buyer_id      INTEGER NOT NULL DEFAULT 0,
    buyer_name    TEXT    NOT NULL DEFAULT '',   -- 下单时的姓名快照，客户改资料不影响历史单
    phone         TEXT    NOT NULL DEFAULT '',   -- 下单时的电话快照
    trade_address TEXT    NOT NULL DEFAULT '',   -- 下单时的交易地点快照
    token_code    TEXT,                          -- 基线兜底：新单不再生成，存量单仍可凭码撤销
    -- 状态机：pending → trading → success / failed
    -- 「取消」不是独立状态，卖家的「取消」就等于把单据置 failed（澄清结论）
    status        TEXT    NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'trading', 'success', 'failed', 'cancelled')),
    trade_result  TEXT
        CHECK (trade_result IN ('success', 'failed')),   -- 只记录有结果的单
    fail_reason   TEXT,                          -- 「库存不足」「买家未到场」等
    created_at    INTEGER NOT NULL,              -- 下单时间；队列主排序键
    finished_at   INTEGER                        -- 交易时间；历史列表排序键
);

INSERT INTO intent__new (id, product_id, buyer_id, buyer_name, phone, trade_address,
                         token_code, status, trade_result, fail_reason, created_at, finished_at)
SELECT id,
       product_id,
       0,
       buyer_name,
       phone,
       '',
       token_code,
       status,
       NULL,
       NULL,
       created_at,
       NULL
FROM intent;

DROP TABLE intent;

ALTER TABLE intent__new RENAME TO intent;

-- --------------------------------------------------------------------------
-- 索引：与查询路径一一对应
-- --------------------------------------------------------------------------
-- 队列：WHERE product_id = ? AND status = 'pending' ORDER BY created_at, id
CREATE INDEX IF NOT EXISTS idx_intent_queue ON intent (product_id, status, created_at);
-- 我的订单：WHERE buyer_id = ? AND status IN ('success','failed') ORDER BY finished_at DESC
CREATE INDEX IF NOT EXISTS idx_intent_buyer ON intent (buyer_id, status, finished_at);
-- 买家（账号模块）查看某客户的购买历史
CREATE INDEX IF NOT EXISTS idx_intent_buyer_id ON intent (buyer_id, created_at);

-- 口令码唯一：基线遗留，新单为 NULL，NULL 不参与唯一比较
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_token ON intent (token_code)
    WHERE token_code IS NOT NULL;

-- 「同一买家 + 同一商品，同时最多 1 条未结束意向」（澄清结论：重复提交需限制）
-- 用部分唯一索引在库层兜底，防止并发下应用层查重被绕过
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_buyer_active ON intent (buyer_id, product_id)
    WHERE status IN ('pending', 'trading') AND buyer_id > 0;

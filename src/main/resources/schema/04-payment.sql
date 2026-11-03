-- ==========================================================================
-- 04-payment.sql　模拟支付与订单状态机（owner：第 5 组 · 模拟支付模块）
-- --------------------------------------------------------------------------
-- 本模块把原「线下交易」（一手交钱一手交货，卖家后台标记 success/failed）
-- 升级为「线上模拟支付」：
--   买家提交意向 → 生成支付单 → 买家支付（模拟支付宝回调）→ 支付成功 → 订单完成。
--
-- 金额约定：与全项目一致，一律存「分」（整数 long），列名 amount_cents，
--   不存 double/元。原因见 common/util/MoneyUtils 的注释：
--   「9.90 用 double 存会变成 9.9000000000000003」，支付金额更不允许浮点误差。
--
-- 支付侧状态机（完整流转图见 PaymentStatus 类注释）：
--   pending ──支付成功──→ paid ──发起退款──→ refunding ──退款成功──→ refunded
--      │                    │                                          （终态）
--      └──支付失败──→ failed└────退款失败────→ paid
-- 订单侧（intent）随之联动：pending ─支付成功→ success ─退款→ failed。
-- ==========================================================================

CREATE TABLE IF NOT EXISTS payment
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 关联购买意向（intent 即「订单」语义）；逻辑外键，物理 REFERENCES 用于完整性
    intent_id    INTEGER NOT NULL REFERENCES intent (id),
    -- 付款人；0 = 匿名（下单必须登录，正常不会出现 0）
    buyer_id     INTEGER NOT NULL DEFAULT 0,
    -- 支付金额，单位「分」；CHECK 强制 > 0
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    -- 支付状态：pending / paid / refunding / refunded / failed
    status       TEXT    NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'paid', 'refunding', 'refunded', 'failed')),
    paid_at      INTEGER,                          -- 支付成功时间（毫秒），未支付为 NULL
    refunded_at  INTEGER,                          -- 退款成功时间（毫秒），未退款为 NULL
    created_at   INTEGER NOT NULL,                 -- 创建时间（毫秒）
    trade_no     TEXT    NOT NULL UNIQUE           -- 模拟支付流水号（时间戳 + 随机数）
);

-- --------------------------------------------------------------------------
-- 索引：与查询路径一一对应
-- --------------------------------------------------------------------------
-- 支付页 / 回调 / 退款都从 intent_id 入手
CREATE INDEX IF NOT EXISTS idx_payment_intent ON payment (intent_id);
-- 越权校验 / 未来「我的支付」列表：按买家 + 状态过滤
CREATE INDEX IF NOT EXISTS idx_payment_buyer ON payment (buyer_id, status);
-- 一张意向最多一张支付单：库层兜底，防止并发下重复生成支付单
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_intent ON payment (intent_id);

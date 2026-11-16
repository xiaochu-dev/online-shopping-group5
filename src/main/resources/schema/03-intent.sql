-- ==========================================================================
-- 03-intent.sql　订单头 / 订单明细（owner：周到）
-- --------------------------------------------------------------------------
-- 迭代 4 · 挑战需求 B.1：intent 由「购买意向」升级为「订单头」，一张订单可含多件商品。
-- 改动清单（对应《挑战需求B-澄清问答记录》§五 表结构总表）：
--   ✔ status 改成五态 created / confirmed / prepared / shipped / completed + 终局 cancelled
--   ✔ 新增 cancelled_by / cancelled_at：客户取消与商家取消的可取消时点不同，文案与审计都要分得开
--   ✔ 新增 order_no：订单号，页面展示与客服核对用（存量单为 NULL）
--   ✔ 新建 order_item：订单明细，一张订单 N 件，价格与名称都存下单时快照
--   ✔ 删除 uq_intent_buyer_active：B 允许多件下单与复购，这条「一人一商品只许一条未结束单」
--     的迭代 1 约束与 B.2.3 直接冲突（记录 §四 R5）。防重复提交改为
--     「同单同商品唯一（uq_order_item）+ 应用层幂等」。
--
-- 迁移期兼容：CHECK 里保留 pending / trading / success / failed 四个旧值。
--   迭代 3 的排队流程（SellerIntentService、PaymentService）还在写它们，T4-03 整段替换后
--   这四个值连同 schema 里的旧值一起删除。新代码只写新值，旧值只可能来自迁移前的存量行。
-- ==========================================================================

-- --------------------------------------------------------------------------
-- intent 订单头（status 由商家按序推进；买卖双方各有一条取消关闸线）
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intent
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no      TEXT,                           -- 订单号，如 SO202611160001；存量单为 NULL
    -- 首件商品：兼容迭代 1~3「一次意向 = 1 件」的展示与索引，订单的完整明细在 order_item
    product_id    INTEGER NOT NULL REFERENCES product (id),
    -- 逻辑外键 → buyer(id)。不写 REFERENCES，避免 01-buyer 与 03-intent 的装载顺序互相牵制
    buyer_id      INTEGER NOT NULL DEFAULT 0,     -- 0 = 迭代 2 基线遗留的匿名单
    buyer_name    TEXT    NOT NULL DEFAULT '',    -- 下单时的姓名快照
    phone         TEXT    NOT NULL DEFAULT '',    -- 下单时的电话快照，不校验格式
    trade_address TEXT    NOT NULL DEFAULT '',    -- 下单时的交易地点快照
    token_code    TEXT,                           -- 基线兜底，新单为 NULL
    -- 状态机：created → confirmed → prepared → shipped → completed
    --        客户在 shipped 之前可取消，商家在 completed 之前可取消，取消落 cancelled（终局）
    status        TEXT    NOT NULL DEFAULT 'created'
        CHECK (status IN ('created', 'confirmed', 'prepared', 'shipped', 'completed', 'cancelled',
                          'pending', 'trading', 'success', 'failed')),
    -- 谁取消的：buyer / seller；未取消为 NULL。存量 failed 单无法区分，迁为 NULL
    cancelled_by  TEXT CHECK (cancelled_by IN ('buyer', 'seller')),
    cancelled_at  INTEGER,
    -- 以下三列 B 起停用：结果已由 status 表达（记录 §五）。保留列是为了存量数据可查，新单不写
    trade_result  TEXT CHECK (trade_result IN ('success', 'failed')),
    fail_reason   TEXT,
    created_at    INTEGER NOT NULL,               -- 下单时间；进行中列表主排序键
    finished_at   INTEGER                         -- 终局时间（completed / cancelled）
);

-- --------------------------------------------------------------------------
-- order_item 订单明细（T4-02）：一张订单可含多件，逐件记数量与价格快照
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_item
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    intent_id     INTEGER NOT NULL REFERENCES intent (id) ON DELETE CASCADE,
    product_id    INTEGER NOT NULL,
    -- 名称与价格都是**下单时**的快照：商品之后改名改价，不影响历史单据
    product_name  TEXT    NOT NULL DEFAULT '',
    price_cents   INTEGER NOT NULL DEFAULT 0,
    quantity      INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at    INTEGER NOT NULL
);

-- 同单同商品只允许一行：购物车里同一商品出现两次时合并数量，不产生两行（记录 §七 第 3 条）
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_item ON order_item (intent_id, product_id);
-- 订单详情：WHERE intent_id = ?
CREATE INDEX IF NOT EXISTS idx_order_item_intent ON order_item (intent_id);

-- --------------------------------------------------------------------------
-- 索引：与查询路径一一对应
-- --------------------------------------------------------------------------
-- 卖家后台：按状态筛选（B.1.1「页面上能按状态筛选」）
CREATE INDEX IF NOT EXISTS idx_intent_status ON intent (status, created_at);
-- 我的订单：WHERE buyer_id = ? ORDER BY finished_at / created_at
CREATE INDEX IF NOT EXISTS idx_intent_buyer ON intent (buyer_id, status, finished_at);
CREATE INDEX IF NOT EXISTS idx_intent_buyer_id ON intent (buyer_id, created_at);
-- 按号码查历史提交（后台核对用）
CREATE INDEX IF NOT EXISTS idx_intent_phone ON intent (phone);
-- 订单号唯一：存量单为 NULL，NULL 不参与唯一比较
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_order_no ON intent (order_no)
    WHERE order_no IS NOT NULL;
-- 口令码唯一：基线遗留，新单为 NULL
CREATE UNIQUE INDEX IF NOT EXISTS uq_intent_token ON intent (token_code)
    WHERE token_code IS NOT NULL;

-- --------------------------------------------------------------------------
-- B 明确删除的约束（记录 §四 R5）
-- 用 DROP 而不是「不创建」：老库上这条索引已经存在，IF NOT EXISTS 删不掉它
-- --------------------------------------------------------------------------
-- 「同一买家 + 同一商品，同时最多 1 条未结束单」——B 允许多件下单与复购，此约束作废
DROP INDEX IF EXISTS uq_intent_buyer_active;
-- 迭代 3 的队列索引（按商品排队），队列机制随 B 整体退出（Q4）
DROP INDEX IF EXISTS idx_intent_queue;

-- ==========================================================================
-- 07-shipping.sql　配送升级（owner：胡成溯 → 挑战需求 C.2）
-- --------------------------------------------------------------------------
-- 本脚本只做两件事，与订单模块解耦：
--   1) buyer_address：一个买家可存多个收货地址，其中至多一个默认
--   2) shipment：一张订单一条发货记录（承运商 + 快递单号 + 发货时间）
--
-- 为什么地址单独建表而不复用 buyer.default_place：
--   C.2.3 要求「多个收货地址，可选一个作为默认」。buyer.default_place 是
--   迭代 1 的单值字段，直接改成多行会破坏下单链路；因此新增本表承载多地址，
--   默认地址变化时同步回写 buyer.default_place，下单链路一行都不用改。
--
-- 为什么发货信息不塞进 intent 表：
--   intent 属订单域（周到）。发货是本模块的职责，另建 shipment 表用
--   intent_id 唯一外键挂上去，订单模块无需感知配送字段。
-- ==========================================================================

-- 收货地址簿
CREATE TABLE IF NOT EXISTS buyer_address
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    buyer_id   INTEGER NOT NULL REFERENCES buyer (id) ON DELETE CASCADE,
    receiver   TEXT    NOT NULL,          -- 收货人姓名
    phone      TEXT    NOT NULL,          -- 收货人电话
    region     TEXT    NOT NULL,          -- 省市区（如「浙江省杭州市西湖区」）
    detail     TEXT    NOT NULL,          -- 详细地址（街道门牌）
    is_default INTEGER NOT NULL DEFAULT 0,-- 1 = 默认地址
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 列表：某买家的地址按默认优先、再按最近修改排序
CREATE INDEX IF NOT EXISTS idx_addr_buyer ON buyer_address (buyer_id, is_default DESC, updated_at DESC);

-- 同一买家至多一个默认地址（部分唯一索引，库层兜底，不靠代码自觉）
CREATE UNIQUE INDEX IF NOT EXISTS uq_addr_default ON buyer_address (buyer_id) WHERE is_default = 1;

-- 发货记录：一张订单至多一条
CREATE TABLE IF NOT EXISTS shipment
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    intent_id   INTEGER NOT NULL UNIQUE REFERENCES intent (id) ON DELETE CASCADE,
    carrier     TEXT    NOT NULL,         -- 承运商代码：SF / JD / ZTO / YTO
    tracking_no TEXT    NOT NULL,         -- 快递单号
    shipped_at  INTEGER NOT NULL,         -- 发货时间（毫秒），物流轨迹以此推算
    created_at  INTEGER NOT NULL
);

-- 买家查物流：按 intent_id 反查；卖家列表：按发货时间倒序
CREATE INDEX IF NOT EXISTS idx_shipment_shipped ON shipment (shipped_at DESC);

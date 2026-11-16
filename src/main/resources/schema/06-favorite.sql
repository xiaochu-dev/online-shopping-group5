-- 挑战需求 B.2.1 / B.2.4：每位买家对每件商品最多收藏一次。
CREATE TABLE IF NOT EXISTS favorite (
    buyer_id   INTEGER NOT NULL REFERENCES buyer(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (buyer_id, product_id)
);

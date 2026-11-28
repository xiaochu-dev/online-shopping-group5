-- 挑战 C.3：按订单商品登记售后，图片随申请一并入库。
CREATE TABLE IF NOT EXISTS after_sales_request
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    intent_id       INTEGER NOT NULL REFERENCES intent(id),
    product_id      INTEGER NOT NULL,
    product_name    TEXT    NOT NULL,
    buyer_id        INTEGER NOT NULL REFERENCES buyer(id),
    seller_id       INTEGER NOT NULL REFERENCES seller(id),
    title           TEXT    NOT NULL,
    description     TEXT    NOT NULL,
    created_at      INTEGER NOT NULL,
    negotiating_at  INTEGER,
    resolved_at     INTEGER,
    outcome         TEXT CHECK (outcome IN ('RETURN', 'REFUND', 'RETURN_REFUND', 'REPLACEMENT', 'REJECTED', 'OTHER')),
    resolution_note TEXT,
    UNIQUE (intent_id, product_id),
    CHECK ((resolved_at IS NULL AND outcome IS NULL AND resolution_note IS NULL)
        OR (resolved_at IS NOT NULL AND negotiating_at IS NOT NULL AND outcome IS NOT NULL AND resolution_note IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_after_sales_buyer ON after_sales_request (buyer_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_after_sales_seller ON after_sales_request (seller_id, id DESC);

CREATE TABLE IF NOT EXISTS after_sales_image
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id  INTEGER NOT NULL REFERENCES after_sales_request(id) ON DELETE CASCADE,
    media_type  TEXT    NOT NULL CHECK (media_type IN ('image/jpeg', 'image/png')),
    content     BLOB    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_after_sales_image_request ON after_sales_image (request_id, id);

package com.group5.shop.aftersales;

import com.group5.shop.aftersales.AfterSalesRequest.Outcome;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Service
public class AfterSalesService {

    private static final int PAGE_SIZE = 15;
    private static final String REQUEST_QUERY = """
            SELECT a.*, i.order_no, i.buyer_name, i.phone
            FROM after_sales_request a
            JOIN intent i ON i.id = a.intent_id
            """;

    private static final RowMapper<AfterSalesRequest> REQUEST_ROW = (rs, row) ->
            new AfterSalesRequest(rs.getLong("id"), rs.getLong("intent_id"), rs.getString("order_no"),
                    rs.getString("product_name"), rs.getString("buyer_name"), rs.getString("phone"),
                    rs.getString("title"), rs.getString("description"), rs.getLong("created_at"),
                    nullableTimestamp(rs.getObject("negotiating_at")), nullableTimestamp(rs.getObject("resolved_at")),
                    rs.getString("outcome") == null ? null : Outcome.valueOf(rs.getString("outcome")),
                    rs.getString("resolution_note"));

    private final JdbcClient jdbc;

    public AfterSalesService(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    static boolean canApply(String status) {
        String currentStatus = IntentStatus.fromLegacy(status);
        return IntentStatus.SHIPPED.equals(currentStatus) || IntentStatus.COMPLETED.equals(currentStatus);
    }

    Optional<Long> existing(long buyerId, long orderId, long productId) {
        return jdbc.sql("""
                SELECT id FROM after_sales_request
                WHERE buyer_id = :buyer AND intent_id = :orderId AND product_id = :product
                """).param("buyer", buyerId).param("orderId", orderId).param("product", productId)
                .query(Long.class).optional();
    }

    Purchase purchase(long buyerId, long orderId, long productId) {
        // 仅在整单没有明细时，才允许用订单头的单件商品作为历史兼容。
        Purchase purchase = jdbc.sql("""
                SELECT i.order_no, i.status, p.seller_id, COALESCE(oi.product_name, p.name) AS product_name,
                       COALESCE(oi.quantity, 1) AS quantity
                FROM intent i
                JOIN product p ON p.id = :product
                LEFT JOIN order_item oi ON oi.intent_id = i.id AND oi.product_id = p.id
                WHERE i.id = :orderId AND i.buyer_id = :buyer
                  AND (oi.id IS NOT NULL OR (i.product_id = p.id AND NOT EXISTS (
                       SELECT 1 FROM order_item other WHERE other.intent_id = i.id)))
                """).param("product", productId).param("orderId", orderId).param("buyer", buyerId)
                .query((rs, row) -> new Purchase(rs.getString("order_no"), rs.getString("status"),
                        rs.getLong("seller_id"), rs.getString("product_name"), rs.getInt("quantity")))
                .optional().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该订单商品"));
        if (!canApply(purchase.status())) {
            throw invalid("已发货或交易完成的订单可以申请售后");
        }
        return purchase;
    }

    @Transactional
    public long submit(long buyerId, long orderId, long productId, String title, String description,
                       List<MultipartFile> files) {
        Optional<Long> previous = existing(buyerId, orderId, productId);
        if (previous.isPresent()) {
            return previous.get();
        }
        Purchase purchase = purchase(buyerId, orderId, productId);
        String subject = requiredText(title, 80, "问题标题");
        String problem = requiredText(description, 2000, "问题描述");
        List<AfterSalesImage> images = AfterSalesImage.readAll(files);

        int inserted = jdbc.sql("""
                INSERT INTO after_sales_request
                    (intent_id, product_id, product_name, buyer_id, seller_id, title, description, created_at)
                VALUES (:orderId, :product, :name, :buyer, :seller, :title, :description, :now)
                ON CONFLICT (intent_id, product_id) DO NOTHING
                """).param("orderId", orderId).param("product", productId).param("name", purchase.productName())
                .param("buyer", buyerId).param("seller", purchase.sellerId()).param("title", subject)
                .param("description", problem).param("now", TimeUtils.nowMillis()).update();
        long requestId = existing(buyerId, orderId, productId).orElseThrow();
        if (inserted == 1) {
            for (AfterSalesImage image : images) {
                jdbc.sql("""
                        INSERT INTO after_sales_image (request_id, media_type, content)
                        VALUES (:requestId, :type, :content)
                        """).param("requestId", requestId).param("type", image.mediaType())
                        .param("content", image.content()).update();
            }
        }
        return requestId;
    }

    @Transactional(readOnly = true)
    public PageResult<AfterSalesRequest> list(Side side, long ownerId, int requestedPage) {
        String owner = "a." + side.ownerColumn + " = :owner";
        long total = jdbc.sql("SELECT COUNT(*) FROM after_sales_request a WHERE " + owner)
                .param("owner", ownerId).query(Long.class).single();
        long page = Math.max(1, Math.min(requestedPage, (total + PAGE_SIZE - 1) / PAGE_SIZE));
        List<AfterSalesRequest> rows = jdbc.sql(REQUEST_QUERY + "WHERE " + owner
                        + " ORDER BY (a.resolved_at IS NOT NULL), a.id DESC LIMIT :size OFFSET :offset")
                .param("owner", ownerId).param("size", PAGE_SIZE).param("offset", (page - 1) * PAGE_SIZE)
                .query(REQUEST_ROW).list();
        return PageResult.of(rows, total, page, PAGE_SIZE);
    }

    AfterSalesRequest detail(Side side, long ownerId, long requestId) {
        return jdbc.sql(REQUEST_QUERY + "WHERE a.id = :id AND a." + side.ownerColumn + " = :owner")
                .param("id", requestId).param("owner", ownerId).query(REQUEST_ROW).optional()
                .orElseThrow(AfterSalesService::notFound);
    }

    List<Long> imageIds(Side side, long ownerId, long requestId) {
        return jdbc.sql("""
                SELECT img.id FROM after_sales_image img JOIN after_sales_request a ON a.id = img.request_id
                WHERE a.id = :id AND a.%s = :owner ORDER BY img.id
                """.formatted(side.ownerColumn)).param("id", requestId).param("owner", ownerId)
                .query(Long.class).list();
    }

    AfterSalesImage image(Side side, long ownerId, long requestId, long imageId) {
        return jdbc.sql("""
                SELECT img.media_type, img.content
                FROM after_sales_image img JOIN after_sales_request a ON a.id = img.request_id
                WHERE a.id = :id AND img.id = :imageId AND a.%s = :owner
                """.formatted(side.ownerColumn)).param("id", requestId).param("imageId", imageId)
                .param("owner", ownerId).query((rs, row) ->
                        new AfterSalesImage(rs.getString("media_type"), rs.getBytes("content")))
                .optional().orElseThrow(AfterSalesService::notFound);
    }

    @Transactional
    public void startNegotiation(long sellerId, long requestId) {
        AfterSalesRequest current = detail(Side.SELLER, sellerId, requestId);
        if (current.resolvedAt() != null) {
            throw invalid("这条售后申请已处理");
        }
        jdbc.sql("""
                UPDATE after_sales_request SET negotiating_at = :now
                WHERE id = :id AND seller_id = :seller AND negotiating_at IS NULL AND resolved_at IS NULL
                """).param("now", TimeUtils.nowMillis()).param("id", requestId).param("seller", sellerId).update();
    }

    @Transactional
    public void resolve(long sellerId, long requestId, Outcome outcome, String note) {
        detail(Side.SELLER, sellerId, requestId);
        if (outcome == null) {
            throw invalid("请选择处理结果");
        }
        String resolution = requiredText(note, 1000, "处理说明");
        int updated = jdbc.sql("""
                UPDATE after_sales_request SET outcome = :outcome, resolution_note = :note, resolved_at = :now
                WHERE id = :id AND seller_id = :seller AND negotiating_at IS NOT NULL AND resolved_at IS NULL
                """).param("outcome", outcome.name()).param("note", resolution).param("now", TimeUtils.nowMillis())
                .param("id", requestId).param("seller", sellerId).update();
        if (updated == 0) {
            throw invalid("只有协商中的申请可以登记结果");
        }
    }

    private static String requiredText(String value, int maxLength, String label) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > maxLength) {
            throw invalid(label + "须为 1～" + maxLength + " 个字符");
        }
        return text;
    }

    private static Long nullableTimestamp(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "售后申请或图片不存在");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_INVALID, message);
    }

    enum Side {
        BUYER("buyer_id"), SELLER("seller_id");

        private final String ownerColumn;

        Side(String ownerColumn) {
            this.ownerColumn = ownerColumn;
        }
    }

    record Purchase(String orderNo, String status, long sellerId, String productName, int quantity) { }
}

package com.group5.shop.portal.adapter;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.portal.domain.OrderLine;
import com.group5.shop.portal.domain.OrderLineMapper;
import com.group5.shop.portal.service.OrderStatusService;
import com.group5.shop.shipping.port.OrderShipPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;

/**
 * 配送侧「订单只读 + 发货推进」端口的实现（提供方：订单域）。
 *
 * <p><b>归属说明</b>：按《迭代4-挑战B-任务工作表》的端口约定，消费方（配送）定义接口、
 * 提供方（订单）实现。本文件由胡成溯代写，<b>订单域 owner（周到）可自由重写</b>，
 * 只要守住三条：① 只返回配送需要的字段，不暴露 {@code Intent} 实体；
 * ② 发货前校验「订单属于该卖家且处于备货完成」；③ 推进复用既有状态机，不绕过它直接改库。</p>
 *
 * <p><b>为什么用 JdbcTemplate 而不是给 {@code IntentMapper} 加方法</b>：
 * 加方法要改订单域的 Mapper，属于跨模块改文件；这里只新增一个适配器文件，
 * 既能满足配送模块的查询，又不触碰他人的类。</p>
 *
 * @author 胡成溯（组长）代写，待周到复核
 */
@Component
public class OrderShipAdapter implements OrderShipPort {

    /** 订单摘要的公共字段（顺序与 {@link #mapRow} 一致） */
    private static final String COLUMNS =
            "i.id, i.order_no, i.buyer_id, i.buyer_name, i.phone, i.trade_address, i.status, i.created_at";

    private final JdbcTemplate jdbc;
    private final OrderLineMapper orderLineMapper;
    private final OrderStatusService orderStatusService;

    public OrderShipAdapter(JdbcTemplate jdbc,
                            OrderLineMapper orderLineMapper,
                            OrderStatusService orderStatusService) {
        this.jdbc = jdbc;
        this.orderLineMapper = orderLineMapper;
        this.orderStatusService = orderStatusService;
    }

    @Override
    public List<OrderBrief> findBySeller(Long sellerId, String status) {
        if (sellerId == null) {
            return List.of();
        }
        String sql = "SELECT DISTINCT " + COLUMNS + " FROM intent i "
                + "WHERE i.status = ? AND ("
                + "  EXISTS (SELECT 1 FROM order_item oi JOIN product p ON p.id = oi.product_id "
                + "          WHERE oi.intent_id = i.id AND p.seller_id = ?) "
                + "  OR EXISTS (SELECT 1 FROM product p2 WHERE p2.id = i.product_id AND p2.seller_id = ?)"
                + ") ORDER BY i.created_at DESC, i.id DESC";
        return rows(jdbc.queryForList(sql, status, sellerId, sellerId));
    }

    @Override
    public List<OrderBrief> findByBuyer(Long buyerId, String status) {
        if (buyerId == null) {
            return List.of();
        }
        if (status == null) {
            String sql = "SELECT " + COLUMNS + " FROM intent i WHERE i.buyer_id = ? "
                    + "ORDER BY i.created_at DESC, i.id DESC";
            return rows(jdbc.queryForList(sql, buyerId));
        }
        String sql = "SELECT " + COLUMNS + " FROM intent i WHERE i.buyer_id = ? AND i.status = ? "
                + "ORDER BY i.created_at DESC, i.id DESC";
        return rows(jdbc.queryForList(sql, buyerId, status));
    }

    @Override
    public OrderBrief findOwned(Long intentId, Long sellerId) {
        if (intentId == null || sellerId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前商家");
        }
        String sql = "SELECT " + COLUMNS + " FROM intent i WHERE i.id = ? AND ("
                + "  EXISTS (SELECT 1 FROM order_item oi JOIN product p ON p.id = oi.product_id "
                + "          WHERE oi.intent_id = i.id AND p.seller_id = ?) "
                + "  OR EXISTS (SELECT 1 FROM product p2 WHERE p2.id = i.product_id AND p2.seller_id = ?)"
                + ")";
        List<Map<String, Object>> list = jdbc.queryForList(sql, intentId, sellerId, sellerId);
        if (list.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前商家");
        }
        return mapRow(list.get(0));
    }

    @Override
    public void markShipped(Long intentId, Long sellerId) {
        OrderBrief brief = findOwned(intentId, sellerId);
        if (!IntentStatus.PREPARED.equals(brief.status())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有「备货完成」的订单才能发货，当前状态：" + IntentStatus.labelOf(brief.status()));
        }
        // 复用既有状态机：prepared → shipped，带状态条件更新，并发下第二次推进会失败
        orderStatusService.advance(intentId, sellerId);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private List<OrderBrief> rows(List<Map<String, Object>> list) {
        List<OrderBrief> result = new ArrayList<>(list.size());
        for (Map<String, Object> row : list) {
            result.add(mapRow(row));
        }
        return result;
    }

    private OrderBrief mapRow(Map<String, Object> row) {
        Long intentId = num(row.get("id"));
        String status = str(row.get("status"));
        return new OrderBrief(
                intentId,
                str(row.get("order_no")),
                num(row.get("buyer_id")),
                str(row.get("buyer_name")),
                str(row.get("phone")),
                str(row.get("trade_address")),
                status,
                row.get("created_at") == null ? 0L : num(row.get("created_at")),
                itemsText(intentId),
                IntentStatus.PREPARED.equals(status));
    }

    /** 明细摘要：优先用 order_item（多件订单），老单没有明细时退回首个商品名 */
    private String itemsText(Long intentId) {
        List<OrderLine> lines = orderLineMapper.selectByIntentId(intentId);
        if (!lines.isEmpty()) {
            List<String> parts = new ArrayList<>(lines.size());
            for (OrderLine line : lines) {
                parts.add(str(line.getProductName()) + " ×" + (line.getQuantity() == null ? 0 : line.getQuantity()));
            }
            return String.join("、", parts);
        }
        List<String> names = jdbc.queryForList(
                "SELECT p.name FROM intent i JOIN product p ON p.id = i.product_id WHERE i.id = ?",
                String.class, intentId);
        return names.isEmpty() ? "" : names.get(0) + " ×1";
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Long num(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}

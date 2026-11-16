package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.OrderLine;
import com.group5.shop.portal.domain.OrderLineMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卖家侧订单查询（T4-04）：我的商品、某商品的订单列表、五态筛选。
 *
 * <h2>迭代 4 删掉了什么</h2>
 * <p>迭代 1~3 的这一层是「排队队列」：{@code 位次}、{@code 只有队首能确认}、
 * {@code 失败塞回队尾}。B 把一次性意向改成订单后，队列这套整段退出——没有队首，
 * 商家对<b>任意一张</b>待确认订单都能直接确认，取消也是独立终局状态而不是「交易失败」。
 * 因此本类不再有条形队列概念，只按状态筛单。</p>
 *
 * <p>状态写入一律走 {@link OrderStatusService}，本类只读。</p>
 *
 * @author 周到（迭代 4 · T4-03 / T4-04）
 */
@Service
public class SellerIntentService {

    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;
    private final OrderLineMapper orderLineMapper;

    public SellerIntentService(IntentMapper intentMapper, ProductMapper productMapper,
                               OrderLineMapper orderLineMapper) {
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
        this.orderLineMapper = orderLineMapper;
    }

    /**
     * 卖家名下全部商品（含已下架），按发布时间倒序。
     *
     * @param sellerId 卖家 id
     * @return 商品列表
     */
    public List<Product> productsOf(Long sellerId) {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getSellerId, sellerId)
                .orderByDesc(Product::getPublishedAt)
                .orderByDesc(Product::getId));
    }

    /**
     * 各商品的<b>未完成订单</b>数（created / confirmed / prepared / shipped）。
     *
     * @return 商品 id → 条数
     */
    public Map<Long, Integer> activeIntentCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : intentMapper.countActiveGroupByProduct()) {
            Object productId = row.get("product_id");
            Object cnt = row.get("cnt");
            if (productId instanceof Number pid && cnt instanceof Number c) {
                counts.put(pid.longValue(), c.intValue());
            }
        }
        return counts;
    }

    /**
     * 某商品的订单列表，可按状态筛选（B.1.1「页面上能按状态筛选」）。
     *
     * <p>不再按提交时间正序排「队」，而是按下单时间<b>倒序</b>：订单列表的常规读法，
     * 最新的在最上面。已取消与已完成的单也保留在列表里，否则商家查不到历史。</p>
     *
     * @param productId 商品 id
     * @param status    状态筛选，null / 空串 = 全部
     * @return 订单列表
     */
    public List<Intent> ordersOfProduct(Long productId, String status) {
        List<Intent> orders = intentMapper.selectByProduct(productId, status);
        attachLines(orders);
        return orders;
    }

    /**
     * 给订单挂明细（一张订单可能含多件商品，B.2.3）。
     *
     * <p>一次批量查、按订单分组，避免「20 行订单 = 20 次查明细」。老意向单没有明细，
     * 挂空列表，模板降级成只显示首件商品。</p>
     */
    private void attachLines(List<Intent> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<Long> intentIds = new ArrayList<>(orders.size());
        for (Intent order : orders) {
            intentIds.add(order.getId());
        }
        Map<Long, List<OrderLine>> grouped = new HashMap<>();
        for (OrderLine line : orderLineMapper.selectByIntentIds(intentIds)) {
            grouped.computeIfAbsent(line.getIntentId(), key -> new ArrayList<>()).add(line);
        }
        for (Intent order : orders) {
            order.setLines(grouped.getOrDefault(order.getId(), List.of()));
        }
    }

    /**
     * 每种状态的订单数，用于筛选标签上的计数。
     *
     * @param productId 商品 id
     * @return 状态值 → 条数；五种状态与 cancelled 都会出现（没有则为 0）
     */
    public Map<String, Integer> statusCounts(Long productId) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : intentMapper.countByProductGroupByStatus(productId)) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status != null && cnt instanceof Number c) {
                counts.put(status.toString(), c.intValue());
            }
        }
        return counts;
    }

    /**
     * 取商品并校验归属，防止卖家操作别人的商品。
     *
     * @param productId 商品 id
     * @param sellerId  当前登录卖家 id
     * @return 商品
     * @throws BusinessException 商品不存在或不属于该卖家
     */
    public Product requireOwnedProduct(Long productId, Long sellerId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        if (sellerId != null && !sellerId.equals(product.getSellerId())) {
            // 单卖家场景下几乎不会触发；多商家入驻后这是越权入口，必须挡住
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    /**
     * 订单所属商品 id，用于动作完成后跳回该商品的订单页。
     *
     * @param intentId 订单 id
     * @return 商品 id；订单不存在返回 {@code null}
     */
    public Long productIdOf(Long intentId) {
        Intent intent = intentMapper.selectById(intentId);
        return intent == null ? null : intent.getProductId();
    }

    /**
     * 商品是否可售（结算页与订单详情页共用）。
     *
     * @param product 商品
     * @return 在售返回 true
     */
    public boolean isOnSale(Product product) {
        return product != null && ProductStatus.ON_SALE.equals(product.getStatus());
    }
}

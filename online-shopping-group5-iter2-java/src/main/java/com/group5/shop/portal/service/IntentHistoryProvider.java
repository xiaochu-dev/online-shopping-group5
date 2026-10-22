package com.group5.shop.portal.service;

import com.group5.shop.admin.port.IntentHistoryItem;
import com.group5.shop.admin.port.IntentHistoryPort;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户详情页「购买历史」的提供方实现（T2-05 的消费端，A.2.3）。
 *
 * <p>接口由账号与客户模块（admin.port）定义、由本模块（intent 的 owner）实现：
 * 这样 admin 不必 import 我的 Intent 实体，我也不用管后台页面怎么排。
 * 他只写 {@code ObjectProvider<IntentHistoryPort>}，我这个 Bean 一注册就自动接上。</p>
 *
 * <p>只返回<b>已结束</b>的单（success / failed），与「我的订单」口径一致：
 * 排队中和交易中的单还没有结果，不算历史。</p>
 *
 * @author 周到
 */
@Service
public class IntentHistoryProvider implements IntentHistoryPort {

    /** 单次最多返回的条数：客户详情页是概览页，不做分页，避免一次拉几千条 */
    private static final int MAX_ROWS = 100;

    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;

    public IntentHistoryProvider(IntentMapper intentMapper, ProductMapper productMapper) {
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
    }

    @Override
    public List<IntentHistoryItem> listByBuyer(Long buyerId) {
        if (buyerId == null) {
            return List.of();
        }

        List<Intent> intents = intentMapper.selectFinishedByBuyer(buyerId, 0L, MAX_ROWS);
        if (intents.isEmpty()) {
            return List.of();
        }

        // 一次批量取商品名，避免逐行查库
        List<Long> productIds = intents.stream().map(Intent::getProductId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        for (Product product : productMapper.selectBatchIds(productIds)) {
            names.put(product.getId(), product.getName());
        }

        List<IntentHistoryItem> items = new ArrayList<>(intents.size());
        for (Intent intent : intents) {
            items.add(new IntentHistoryItem(
                    names.getOrDefault(intent.getProductId(), "（商品已不可见）"),
                    intent.getCreatedAt(),
                    intent.getTradeResult(),
                    intent.getFinishedAt(),
                    intent.getFailReason()));
        }
        return items;
    }
}

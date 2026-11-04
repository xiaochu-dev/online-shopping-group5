package com.group5.shop.portal.service;

import com.group5.shop.product.port.IntentCountPort;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 商品待处理意向数的提供方实现（迭代 3 商品列表补强）。
 *
 * <p>接口由商品中心（{@code product.port}）定义、由本模块（{@code intent} 表的 owner）实现：
 * 这样 product 不必 import 我的 Intent 实体，我也不用管他的商品列表怎么排。
 * 他写 {@code ObjectProvider<IntentCountPort>}，我这个 Bean 一注册就自动接上。</p>
 *
 * <p>与 {@code admin.port.IntentHistoryPort} 的做法一致——跨模块只走接口，
 * 不碰对方的表与实体（任务表 v4.0 硬规则第 2 条）。</p>
 *
 * <p>口径与卖家后台的队列一致：只统计 <b>排队中（pending）与交易中（trading）</b> 的意向；
 * 已成功 / 已失败 / 已撤销的都不算「待处理」。</p>
 *
 * @author 周到
 */
@Service
public class IntentCountProvider implements IntentCountPort {

    private final SellerIntentService sellerIntentService;

    public IntentCountProvider(SellerIntentService sellerIntentService) {
        this.sellerIntentService = sellerIntentService;
    }

    @Override
    public Map<Long, Integer> countActiveGroupByProduct() {
        // 直接复用既有服务，保证与卖家后台「交易与意向」页的统计口径完全一致，
        // 避免两处各写一套 SQL 后数字对不上。
        return sellerIntentService.activeIntentCounts();
    }
}

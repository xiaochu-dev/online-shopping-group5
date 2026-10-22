package com.group5.shop.product.contract;

/** 商品中心只通知队列 owner，不跨模块写 intent 表。 */
public interface IntentQueuePort {
    int failByProduct(Long productId, String reason);
    boolean hasTrading(Long productId);
}

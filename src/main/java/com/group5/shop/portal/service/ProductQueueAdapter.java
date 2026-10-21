package com.group5.shop.portal.service;

import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.contract.IntentQueuePort;
import org.springframework.stereotype.Service;

/** 交接适配：使用交易模块已有的批量结束能力，不把 Mapper 暴露给商品模块。 */
@Service
public class ProductQueueAdapter implements IntentQueuePort {
    private final IntentMapper mapper;
    public ProductQueueAdapter(IntentMapper mapper) { this.mapper = mapper; }
    @Override
    public boolean hasTrading(Long productId) {
        return mapper.selectActiveQueue(productId).stream().anyMatch(i -> "trading".equals(i.getStatus()));
    }
    @Override
    public int failByProduct(Long productId, String reason) {
        return mapper.failPendingOfProduct(productId, reason, System.currentTimeMillis());
    }
}

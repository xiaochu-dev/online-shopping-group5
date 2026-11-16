package com.group5.shop.portal.contract.adapter;

import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.portal.contract.SellerContext;
import com.group5.shop.portal.port.StockBatchPort;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.web.context.request.*;

/** 只保留账号模块尚未提供正式接口的卖家上下文；商品能力已接入正式服务。 */
@Configuration
public class ContractDefaults {
    @Bean
    @ConditionalOnMissingBean(SellerContext.class)
    public SellerContext sellerContextDefault() {
        return new SellerContext() {
            @Override public Long currentId() {
                Object id=attribute(SessionKeys.LOGIN_SELLER_ID);
                return id==null?null:((Number)id).longValue();
            }
            @Override public String currentName() {
                Object name=attribute(SessionKeys.LOGIN_SELLER);
                return name==null?null:name.toString();
            }
        };
    }

    /**
     * 库存批量端口未接入时的兜底（T4-23 之前）。
     *
     * <p><b>刻意抛异常而不是返回「全部有货」</b>：假装有货会让超卖悄悄发生，
     * 比明确报错危险得多。宁可结算时看到「库存服务未接入」，也不能让脏数据流进库里。</p>
     */
    @Bean
    @ConditionalOnMissingBean(StockBatchPort.class)
    public StockBatchPort stockBatchPortDefault() {
        return new StockBatchPort() {
            @Override
            public DeductResult deduct(java.util.List<Demand> demands) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "库存批量服务尚未接入（StockBatchPort 待商品域实现）");
            }
            @Override
            public void restore(Long orderId, java.util.List<Demand> demands) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "库存回补服务尚未接入（StockBatchPort 待商品域实现）");
            }
        };
    }

    /*
     * 下单端口不在这里兜底：购物车那边（cart/port/OrderCreatePort）用 ObjectProvider
     * 取实现，取不到时结算按钮给出「下单功能尚未接入」。这里再放一个「一调用就抛异常」
     * 的假 Bean，反而会让 getIfAvailable() 永远非空，把这条降级路径堵死。
     */

    private static Object attribute(String key) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpSession session=attrs.getRequest().getSession(false);
        return session==null?null:session.getAttribute(key);
    }
}

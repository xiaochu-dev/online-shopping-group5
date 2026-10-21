package com.group5.shop.portal.contract.adapter;

import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.portal.contract.SellerContext;
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
    private static Object attribute(String key) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        HttpSession session=attrs.getRequest().getSession(false);
        return session==null?null:session.getAttribute(key);
    }
}

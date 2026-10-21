package com.group5.shop.buyer.config;

import com.group5.shop.buyer.interceptor.BuyerLoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 买家模块的 MVC 配置（T2-03）。
 *
 * <p>刻意不写进 {@code common/config/WebMvcConfig}：那个类属于公共层，
 * 本迭代已冻结（见任务表 v4.0「四条硬规则」第 4 条）。
 * Spring 允许多个 {@link WebMvcConfigurer} 同时存在，
 * 各模块注册自己的拦截器，改动互不影响。</p>
 *
 * @author 胡成溯（组长）
 */
@Configuration
public class BuyerWebConfig implements WebMvcConfigurer {

    private final BuyerLoginInterceptor buyerLoginInterceptor;

    public BuyerWebConfig(BuyerLoginInterceptor buyerLoginInterceptor) {
        this.buyerLoginInterceptor = buyerLoginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(buyerLoginInterceptor)
                // 买家中心 + 我的订单（/my/** 由周到在 portal 模块实现）
                .addPathPatterns("/buyer/me", "/my", "/my/**")
                // 登录、注册、退出、静态资源必须放行，否则会形成跳转死循环
                .excludePathPatterns(
                        "/buyer/login",
                        "/buyer/register",
                        "/buyer/logout",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/uploads/**",
                        "/error");
    }
}

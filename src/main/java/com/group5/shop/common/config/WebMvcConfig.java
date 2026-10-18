package com.group5.shop.common.config;

import com.group5.shop.auth.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置（T2-01 / T2-02 / T2-05）。
 *
 * <p>包含两件事：
 * <ol>
 *   <li>注册登录拦截器，只拦 {@code /admin/**}（后台）。买家门户与登录页是公开的，不能拦。</li>
 *   <li>把磁盘目录 {@code ./uploads/} 映射成 {@code /uploads/**}，
 *       让后台上传的商品图片能被浏览器直接访问。</li>
 * </ol>
 * </p>
 *
 * @author 胡成溯（组长）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    public WebMvcConfig(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                // 后台全部受保护（含 /admin、/admin/password、黄程宇与周到的 /admin/** 子路径）
                .addPathPatterns("/admin", "/admin/**")
                // 明确排除登录页本身，否则会形成「跳登录页 → 又被拦截 → 再跳登录页」的死循环
                .excludePathPatterns("/login", "/logout", "/error");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传目录：商品图片存 ./uploads/xxx.png，页面用 /uploads/xxx.png 访问
        // 注意 file: 前缀与结尾的斜杠都不可省略，否则 Spring 会把相对路径解析错
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:./uploads/");
    }
}

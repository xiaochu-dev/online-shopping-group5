package com.group5.shop.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

/**
 * 请求判定工具。
 *
 * <p>全项目只有一条「请求类型判定口径」，异常处理与登录拦截器共用，
 * 避免两处规则不一致导致「接口报 302 跳登录页」这类诡异问题。</p>
 *
 * <p>判定为「接口请求」的条件（满足任一即可）：
 * <ol>
 *   <li>URI 以 {@code /api/} 开头（团队约定的 JSON 接口前缀）；</li>
 *   <li>请求头 {@code X-Requested-With: XMLHttpRequest}（前端 ajax）；</li>
 *   <li>{@code Accept} 含 {@code application/json}。</li>
 * </ol>
 * 其余一律按「页面请求」处理（返回 HTML / 302 跳转）。</p>
 *
 * @author 胡成溯（组长）
 */
public final class RequestUtils {

    private RequestUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /** 是否为接口请求（应返回 JSON） */
    public static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return true;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
    }
}

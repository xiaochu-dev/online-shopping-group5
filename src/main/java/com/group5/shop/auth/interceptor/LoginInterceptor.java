package com.group5.shop.auth.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group5.shop.common.api.ApiResponse;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 登录拦截器（T2-05，对应 FR-01-3「未登录访问后台被拦截」）。
 *
 * <p>拦截范围：{@code /admin/**}（在 {@link com.group5.shop.common.config.WebMvcConfig} 中注册）。</p>
 *
 * <p>两种出口：
 * <ul>
 *   <li>页面请求 → 302 跳 {@code /login?redirect=原地址}，登录成功后自动跳回；</li>
 *   <li>接口请求 → 401 + {@code ApiResponse} JSON，前端可据此提示「请先登录」。</li>
 * </ul>
 * 如果接口请求也返回 302，前端 fetch 会拿到一段登录页 HTML，
 * 解析 JSON 时抛「Unexpected token &lt;」，排查起来非常痛苦，所以必须分开处理。</p>
 *
 * <p>为什么 {@code getSession(false)} 而不是 {@code getSession()}：
 * 后者在会话不存在时会<b>新建</b>一个空会话，导致每个未登录访客都在内存里留下一个 session，
 * 既浪费内存又容易误判「有会话=已登录」。</p>
 *
 * @author 胡成溯（组长）
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        Object username = (session == null) ? null : session.getAttribute(SessionKeys.LOGIN_SELLER);

        if (username != null) {
            // 已登录，放行
            return true;
        }

        log.debug("未登录访问受保护资源：{} {}", request.getMethod(), request.getRequestURI());

        if (RequestUtils.isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            ApiResponse<Void> body = ApiResponse.fail(
                    ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }

        response.sendRedirect(request.getContextPath() + "/login?redirect=" + buildRedirectTarget(request));
        return false;
    }

    /** 把原始访问地址编码进 redirect 参数，登录成功后原样跳回 */
    private String buildRedirectTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = (query == null || query.isBlank()) ? uri : uri + "?" + query;
        return URLEncoder.encode(target, StandardCharsets.UTF_8);
    }
}

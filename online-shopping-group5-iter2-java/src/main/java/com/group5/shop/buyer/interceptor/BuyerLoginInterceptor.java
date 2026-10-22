package com.group5.shop.buyer.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.common.api.ApiResponse;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 买家登录拦截器（T2-03）。
 *
 * <p>拦截范围：{@code /my/**}（买家的「我的订单」等页面，由周到实现）、{@code /buyer/me}。
 * <b>商品浏览与详情是公开的，不能拦</b>——澄清结论明确「浏览不拦截，下单才要求登录」。</p>
 *
 * <p>两种出口与卖家拦截器保持一致：页面 302 跳 {@code /buyer/login?redirect=原地址}，
 * 接口 401 + JSON。</p>
 *
 * <p>登录态判定委托给 {@link BuyerSessionCookie}，本类不关心签名细节；
 * 校验通过后把买家 id 放进 request attribute，方便下游（含 {@code BuyerContext}）取用。</p>
 *
 * @author 胡成溯（组长）
 */
@Component
public class BuyerLoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BuyerLoginInterceptor.class);

    /** request attribute：当前登录买家 id */
    public static final String ATTR_BUYER_ID = "CURRENT_BUYER_ID";

    private final BuyerSessionCookie sessionCookie;
    private final ObjectMapper objectMapper;

    public BuyerLoginInterceptor(BuyerSessionCookie sessionCookie, ObjectMapper objectMapper) {
        this.sessionCookie = sessionCookie;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        var buyerId = sessionCookie.resolve(request);
        if (buyerId.isPresent()) {
            request.setAttribute(ATTR_BUYER_ID, buyerId.get());
            return true;
        }

        log.debug("未登录访问买家受保护资源：{} {}", request.getMethod(), request.getRequestURI());

        if (RequestUtils.isApiRequest(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            ApiResponse<Void> body = ApiResponse.fail(
                    ErrorCode.UNAUTHORIZED.getCode(), "请先登录买家账号");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }

        response.sendRedirect(request.getContextPath()
                + "/buyer/login?redirect=" + buildRedirectTarget(request));
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

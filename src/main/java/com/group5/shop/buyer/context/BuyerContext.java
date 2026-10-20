package com.group5.shop.buyer.context;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * 当前登录买家上下文（T2-03）——**跨模块唯一出口**。
 *
 * <p>买家门户（周到）需要知道「谁在下单」时，只调 {@code buyerContext.current()}，
 * 不要自己读 cookie、也不要直接查 {@code buyer} 表：
 * 登录态怎么存是本模块的实现细节，改了不该影响别人。</p>
 *
 * <p>用法：</p>
 * <pre>
 * Buyer buyer = buyerContext.current();
 * if (buyer == null) {  // 未登录，跳 /buyer/login?redirect=当前地址
 *     ...
 * }
 * </pre>
 *
 * <p>每次请求只查一次库：结果缓存在 request attribute 上，同一请求内多次调用不会重复查询。</p>
 *
 * @author 胡成溯（组长）
 */
@Component
public class BuyerContext {

    /** request attribute 名，用于同一请求内缓存买家对象 */
    private static final String ATTR_BUYER = "CURRENT_BUYER";

    /** 缓存「已查过但没有」的标记，避免未登录请求反复查库 */
    private static final String ATTR_RESOLVED = "CURRENT_BUYER_RESOLVED";

    private final BuyerSessionCookie sessionCookie;
    private final BuyerMapper buyerMapper;

    public BuyerContext(BuyerSessionCookie sessionCookie, BuyerMapper buyerMapper) {
        this.sessionCookie = sessionCookie;
        this.buyerMapper = buyerMapper;
    }

    /**
     * 当前登录买家。
     *
     * @return 买家对象；未登录 / 登录态失效 / 买家已被删除时返回 {@code null}
     */
    public Buyer current() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object cached = request.getAttribute(ATTR_BUYER);
        if (cached instanceof Buyer buyer) {
            return buyer;
        }
        if (Boolean.TRUE.equals(request.getAttribute(ATTR_RESOLVED))) {
            return null;
        }

        request.setAttribute(ATTR_RESOLVED, Boolean.TRUE);
        Optional<Long> buyerId = sessionCookie.resolve(request);
        if (buyerId.isEmpty()) {
            return null;
        }
        Buyer buyer = buyerMapper.selectById(buyerId.get());
        if (buyer != null) {
            request.setAttribute(ATTR_BUYER, buyer);
        }
        return buyer;
    }

    /**
     * 当前登录买家 id。
     *
     * @return 买家 id；未登录返回 {@code null}
     */
    public Long currentId() {
        Buyer buyer = current();
        return buyer == null ? null : buyer.getId();
    }

    /** 取当前线程绑定的请求；非 Web 线程（如定时任务）返回 null */
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}

package com.group5.shop.portal.controller;

import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.contract.SellerContext;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.service.OrderStatusService;
import com.group5.shop.portal.service.SellerIntentService;
import com.group5.shop.product.domain.Product;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 卖家订单管理（T2-18 起；T4-03 / T4-04 改为五态）。
 *
 * <p><b>路径为什么在 {@code /admin/} 下</b>：操作者是卖家，鉴权来自账号模块，
 * 挂在 {@code /admin/**} 下可直接复用登录拦截器。代码包仍留在 {@code portal/}，
 * 因为 {@code intent} 表归订单域。</p>
 *
 * <table border="1">
 *   <caption>路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>功能</th></tr>
 *   <tr><td>GET</td><td>/admin/intents</td><td>我的商品列表（含未完成订单数）</td></tr>
 *   <tr><td>GET</td><td>/admin/intents/{productId}?status=</td><td>某商品的订单，可按五态筛选</td></tr>
 *   <tr><td>POST</td><td>/admin/intents/{intentId}/advance</td><td>推进一步（确认 → 备货 → 发货 → 完成）</td></tr>
 *   <tr><td>POST</td><td>/admin/intents/{intentId}/cancel</td><td>商家取消（交易完成前）</td></tr>
 * </table>
 *
 * <p>迭代 3 的 {@code /confirm}、{@code /success}、{@code /fail}（含「塞回队尾」）三个
 * 动作随队列机制一起下线：B 里商家是「一次一步往前走」，取消是独立终局，
 * 没有「失败后塞回队尾」这种操作。</p>
 *
 * @author 周到（迭代 4 · T4-04）
 */
@Controller
public class SellerIntentController {

    private final SellerIntentService sellerIntentService;
    private final OrderStatusService orderStatusService;
    private final SellerContext sellerContext;

    public SellerIntentController(SellerIntentService sellerIntentService,
                                  OrderStatusService orderStatusService,
                                  SellerContext sellerContext) {
        this.sellerIntentService = sellerIntentService;
        this.orderStatusService = orderStatusService;
        this.sellerContext = sellerContext;
    }

    // ==================================================================
    // 商品列表
    // ==================================================================

    /** 我的商品：显示状态与未完成订单数 */
    @GetMapping("/admin/intents")
    public String productList(@RequestParam(value = "msg", required = false) String msg,
                              @RequestParam(value = "error", required = false) String error,
                              Model model) {
        Long sellerId = sellerContext.currentId();
        List<Product> products = sellerIntentService.productsOf(sellerId);
        Map<Long, Integer> counts = sellerIntentService.activeIntentCounts();

        model.addAttribute("products", products);
        model.addAttribute("counts", counts);
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("sellerName", sellerContext.currentName());
        return "portal/seller-products";
    }

    // ==================================================================
    // 某商品的订单列表
    // ==================================================================

    @GetMapping("/admin/intents/{productId}")
    public String orders(@PathVariable("productId") Long productId,
                         @RequestParam(value = "status", required = false) String status,
                         @RequestParam(value = "msg", required = false) String msg,
                         @RequestParam(value = "error", required = false) String error,
                         Model model) {
        Long sellerId = sellerContext.currentId();
        Product product = sellerIntentService.requireOwnedProduct(productId, sellerId);
        String filter = normalizeStatus(status);
        List<Intent> orders = sellerIntentService.ordersOfProduct(productId, filter);

        model.addAttribute("product", product);
        model.addAttribute("orders", orders);
        model.addAttribute("statusFilter", filter);
        model.addAttribute("statusCounts", sellerIntentService.statusCounts(productId));
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("sellerName", sellerContext.currentName());
        return "portal/seller-intents";
    }

    // ==================================================================
    // 两个动作
    // ==================================================================

    /**
     * 推进一步：created → confirmed → prepared → shipped → completed
     *
     * <p>{@code productId} 是卖家<b>当前所在的商品页</b>，用来「从哪来回哪去」：
     * 多件订单在它涉及的每个商品页都能看到，在机械键盘页点确认后不该被甩到算法导论页。
     * 缺省时回落到订单首件商品。</p>
     */
    @PostMapping("/admin/intents/{intentId}/advance")
    public String advance(@PathVariable("intentId") Long intentId,
                          @RequestParam(value = "productId", required = false) Long productId,
                          @RequestParam(value = "status", required = false) String status) {
        Long sellerId = sellerContext.currentId();
        Long target = productId != null ? productId : sellerIntentService.productIdOf(intentId);
        return act(target, normalizeStatus(status),
                () -> orderStatusService.advance(intentId, sellerId),
                "ok", "已推进到「%s」");
    }

    /**
     * 商家取消：交易完成之前都可以（含已发货）。
     *
     * @param reason 取消原因，可空
     */
    @PostMapping("/admin/intents/{intentId}/cancel")
    public String cancel(@PathVariable("intentId") Long intentId,
                         @RequestParam(value = "reason", required = false) String reason,
                         @RequestParam(value = "productId", required = false) Long productId,
                         @RequestParam(value = "status", required = false) String status) {
        Long sellerId = sellerContext.currentId();
        Long target = productId != null ? productId : sellerIntentService.productIdOf(intentId);
        return act(target, normalizeStatus(status),
                () -> orderStatusService.cancelBySeller(intentId, sellerId, reason),
                "已取消该订单，库存已回补", null);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 统一处理「执行动作 → 303 回该商品的订单页」。
     *
     * <p>失败也回列表并在 {@code error} 里说明原因，不抛到错误页——
     * 卖家最常见的失败是「状态已被改过」和「越权操作别人的单」，这两种都应就地提示。</p>
     *
     * <p>{@code messageTemplate} 含 {@code %s} 时用动作后的状态中文名填充。</p>
     */
    private String act(Long productId, String status, Supplier<Intent> action, String successMessage,
                       String messageTemplate) {
        Intent after;
        try {
            after = action.get();
        } catch (BusinessException e) {
            return backTo(productId, status, "error", e.getMessage());
        }
        String message = messageTemplate == null
                ? successMessage
                : String.format(messageTemplate, IntentStatus.labelOf(after.getStatus()));
        return backTo(productId, status, "msg", message);
    }

    private String backTo(Long productId, String status, String key, String text) {
        StringBuilder url = new StringBuilder("redirect:/admin/intents");
        if (productId != null) {
            url.append('/').append(productId);
        }
        StringBuilder query = new StringBuilder();
        if (status != null && !status.isEmpty()) {
            query.append("status=").append(status);
        }
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(key).append('=').append(encode(text));
        return url.append('?').append(query).toString();
    }

    /** 只接受五种流转态与 cancelled，其他值视为「不筛选」，避免把任意串拼进 SQL */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim();
        return switch (value) {
            case IntentStatus.CREATED, IntentStatus.CONFIRMED, IntentStatus.PREPARED,
                 IntentStatus.SHIPPED, IntentStatus.COMPLETED, IntentStatus.CANCELLED -> value;
            default -> null;
        };
    }

    private String encode(String text) {
        return UriUtils.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

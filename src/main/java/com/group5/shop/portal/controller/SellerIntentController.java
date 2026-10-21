package com.group5.shop.portal.controller;

import com.group5.shop.common.entity.Product;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.contract.SellerContext;
import com.group5.shop.portal.domain.QueueRow;
import com.group5.shop.portal.service.SellerIntentService;
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

/**
 * 卖家交易确认与意向管理（T2-18）。
 *
 * <p><b>路径为什么在 {@code /admin/} 下</b>：这一页的操作者是卖家，鉴权来自账号模块。
 * 挂在 {@code /admin/**} 下可以直接复用已有的登录拦截器（{@code LoginInterceptor}），
 * 不必为它再写一套权限判断。代码包仍留在 {@code portal/}，因为 intent 表归门户模块，
 * 真正被修改的是意向状态机。</p>
 *
 * <table border="1">
 *   <caption>路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>功能</th></tr>
 *   <tr><td>GET</td><td>/admin/intents</td><td>我的商品列表（含待处理意向数）</td></tr>
 *   <tr><td>GET</td><td>/admin/intents/{productId}</td><td>某商品的意向队列</td></tr>
 *   <tr><td>POST</td><td>/admin/intents/{intentId}/confirm</td><td>确认交易（冻结商品）</td></tr>
 *   <tr><td>POST</td><td>/admin/intents/{intentId}/success</td><td>交易成功（扣 1 件）</td></tr>
 *   <tr><td>POST</td><td>/admin/intents/{intentId}/fail</td><td>交易失败（作废 / 塞回队尾）</td></tr>
 * </table>
 *
 * @author 周到
 */
@Controller
public class SellerIntentController {

    private final SellerIntentService sellerIntentService;
    private final SellerContext sellerContext;

    public SellerIntentController(SellerIntentService sellerIntentService,
                                  SellerContext sellerContext) {
        this.sellerIntentService = sellerIntentService;
        this.sellerContext = sellerContext;
    }

    // ==================================================================
    // 商品列表
    // ==================================================================

    /** 我的商品：本迭代无删除功能，卖完的商品会留着，所以必须显示状态与待处理数 */
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
    // 某商品的队列
    // ==================================================================

    @GetMapping("/admin/intents/{productId}")
    public String queue(@PathVariable("productId") Long productId,
                        @RequestParam(value = "msg", required = false) String msg,
                        @RequestParam(value = "error", required = false) String error,
                        Model model) {
        Long sellerId = sellerContext.currentId();
        Product product = sellerIntentService.requireOwnedProduct(productId, sellerId);
        List<QueueRow> queue = sellerIntentService.queueOf(productId);

        model.addAttribute("product", product);
        model.addAttribute("queue", queue);
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("sellerName", sellerContext.currentName());
        return "portal/seller-intents";
    }

    // ==================================================================
    // 三个动作
    // ==================================================================

    /** 确认交易：队首 → 交易中，商品冻结 */
    @PostMapping("/admin/intents/{intentId}/confirm")
    public String confirm(@PathVariable("intentId") Long intentId) {
        return act(intentId, () -> sellerIntentService.confirmTrade(intentId),
                "已确认交易，商品进入「交易中」，其他买家暂时无法下单");
    }

    /** 交易成功：扣 1 件库存，订单置成功，商品解冻回在售 */
    @PostMapping("/admin/intents/{intentId}/success")
    public String success(@PathVariable("intentId") Long intentId) {
        return act(intentId, () -> sellerIntentService.markSuccess(intentId),
                "已标记交易成功，库存扣减 1 件");
    }

    /**
     * 交易失败：库存不动，商品解冻回在售，并对该买家二选一。
     *
     * @param requeue true = 塞回队尾；false / 不传 = 作废
     * @param reason  失败原因，可空
     */
    @PostMapping("/admin/intents/{intentId}/fail")
    public String fail(@PathVariable("intentId") Long intentId,
                       @RequestParam(value = "requeue", required = false) Boolean requeue,
                       @RequestParam(value = "reason", required = false) String reason) {
        boolean back = Boolean.TRUE.equals(requeue);
        String done = back
                ? "已标记交易失败，该买家已塞回队尾重新排队"
                : "已标记交易失败并作废该买家的意向";
        return act(intentId, () -> sellerIntentService.markFailed(intentId, back, reason), done);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 统一处理「执行动作 → 303 回该商品的队列页」。
     *
     * <p>失败也回队列页并在 {@code error} 里说明原因，不抛到错误页——
     * 卖家最常见的失败是「跳着确认队首」和「商品已在交易中」，这两种都应就地提示、就地重试。</p>
     */
    private String act(Long intentId, Runnable action, String successMessage) {
        Long productId = sellerIntentService.productIdOf(intentId);
        try {
            action.run();
        } catch (BusinessException e) {
            return backTo(productId, "error", e.getMessage());
        }
        return backTo(productId, "msg", successMessage);
    }

    private String backTo(Long productId, String key, String text) {
        String suffix = "?" + key + "=" + encode(text);
        return productId == null
                ? "redirect:/admin/intents" + suffix
                : "redirect:/admin/intents/" + productId + suffix;
    }

    private String encode(String text) {
        return UriUtils.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

package com.group5.shop.payment.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.payment.domain.Payment;
import com.group5.shop.payment.service.PaymentService;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 买家支付控制器（第 5 组 · 模拟支付模块）。
 *
 * <p><b>必须登录 + 只能操作自己的单</b>：登录态走 {@link BuyerContext}（与门户下单同款）；
 * 归属校验在 {@link PaymentService} 里做（查不到/不属于自己一律按不存在处理）。</p>
 *
 * <table border="1">
 *   <caption>路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>功能</th></tr>
 *   <tr><td>GET</td><td>/pay/{intentId}</td><td>支付页（自动生成支付单，幂等）</td></tr>
 *   <tr><td>POST</td><td>/pay/{intentId}/pay</td><td>模拟支付（等价支付宝支付成功回调）</td></tr>
 *   <tr><td>POST</td><td>/pay/{intentId}/refund</td><td>申请退款</td></tr>
 * </table>
 *
 * <p>动作成功后统一 {@code Post/Redirect/Get} 回支付页并带 {@code msg} / {@code error}，
 * 失败也回支付页就地提示，不抛到全局错误页——买家最常见的失败是「重复支付 / 状态已变」，
 * 应就地重试而不是看到一个 500。</p>
 *
 * @author 第 5 组（模拟支付模块）
 */
@Controller
public class PaymentController {

    /** 买家登录页路径，与门户模块保持一致 */
    private static final String BUYER_LOGIN_PATH = "/buyer/login";

    private final PaymentService paymentService;
    private final BuyerContext buyerContext;
    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;

    public PaymentController(PaymentService paymentService,
                             BuyerContext buyerContext,
                             IntentMapper intentMapper,
                             ProductMapper productMapper) {
        this.paymentService = paymentService;
        this.buyerContext = buyerContext;
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
    }

    // ==================================================================
    // 支付页
    // ==================================================================

    /**
     * 支付页：生成（或取出）该意向的支付单，展示金额、流水号、状态与对应按钮。
     *
     * <p>未登录跳买家登录页并带回跳地址；登录后 {@code createPayment} 是幂等的，
     * 反复刷新不会重复生成支付单。</p>
     */
    @GetMapping("/pay/{intentId}")
    public String page(@PathVariable("intentId") Long intentId,
                       @RequestParam(value = "msg", required = false) String msg,
                       @RequestParam(value = "error", required = false) String error,
                       Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/pay/" + intentId);
        }

        Payment payment = paymentService.createPayment(intentId, buyer);
        model.addAttribute("payment", payment);
        model.addAttribute("productName", productNameOf(intentId));
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("buyerName", buyer.getUsername());
        return "payment/payment";
    }

    // ==================================================================
    // 模拟支付（支付宝支付成功回调）
    // ==================================================================

    /** 模拟支付成功：支付单 pending → paid，订单 pending → success（自动完成） */
    @PostMapping("/pay/{intentId}/pay")
    public String pay(@PathVariable("intentId") Long intentId) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/pay/" + intentId);
        }
        return act(intentId, () -> paymentService.mockPayCallback(intentId, buyer),
                "支付成功，订单已完成。可在「我的订单」查看。");
    }

    // ==================================================================
    // 申请退款
    // ==================================================================

    /** 申请退款：支付单 paid → refunding → refunded，订单 success → failed */
    @PostMapping("/pay/{intentId}/refund")
    public String refund(@PathVariable("intentId") Long intentId) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/pay/" + intentId);
        }
        return act(intentId, () -> paymentService.refund(intentId, buyer),
                "退款成功，订单已回退为失败。");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 统一处理「执行动作 → 303 回支付页」，失败就地带 error 提示 */
    private String act(Long intentId, Runnable action, String successMessage) {
        try {
            action.run();
        } catch (BusinessException e) {
            return "redirect:/pay/" + intentId + "?error=" + encode(e.getMessage());
        }
        return "redirect:/pay/" + intentId + "?msg=" + encode(successMessage);
    }

    /** 支付页展示商品名：意向 → 商品，商品不存在给兜底文案 */
    private String productNameOf(Long intentId) {
        Intent intent = intentMapper.selectById(intentId);
        if (intent == null) {
            return "（订单不存在）";
        }
        Product product = productMapper.selectById(intent.getProductId());
        return product == null ? "（商品已下架）" : product.getName();
    }

    /** 中文提示参数必须按 UTF-8 编码，否则在重定向 URL 里会变成乱码 */
    private String encode(String text) {
        return UriUtils.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

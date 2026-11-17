package com.group5.shop.cart.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.cart.domain.Cart;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.cart.service.CartService;
import com.group5.shop.cart.service.FavoriteMoveResult;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.util.MoneyUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 购物车控制器（挑战需求 B.2.2 / B.2.3 / B.2.4，owner：胡成溯）。
 *
 * <p><b>登录要求</b>：购物车是「我的」数据，五个入口都要求买家登录。这里没有把
 * {@code /cart} 塞进 {@code BuyerLoginInterceptor} 的路径列表——那属于基座公共配置，
 * 本迭代冻结；改在控制器里做同样的判断，效果一致且不动别人的文件。</p>
 *
 * <p><b>错误展示</b>：业务异常统一转成 {@code ?error=} 重定向回原页面，不跳错误页。
 * 加购失败这类问题应当「停在原地告诉用户原因」，而不是把整个页面换成错误页。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
@RequestMapping("/cart")
public class CartController {

    /** 买家登录页，与 {@code MyOrdersController} 保持一致 */
    private static final String BUYER_LOGIN_PATH = "/buyer/login";

    private final CartService cartService;
    private final BuyerContext buyerContext;

    public CartController(CartService cartService, BuyerContext buyerContext) {
        this.cartService = cartService;
        this.buyerContext = buyerContext;
    }

    /** 购物车页 */
    @GetMapping
    public String list(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/cart");
        }

        List<Cart> rows = cartService.list(buyer.getId());
        model.addAttribute("rows", rows);
        model.addAttribute("buyerName", buyer.getUsername());
        model.addAttribute("totalYuan", MoneyUtils.centsToYuan(cartService.totalCents(rows)));
        model.addAttribute("checkoutAvailable", cartService.checkoutAvailable());
        model.addAttribute("favoriteAvailable", cartService.favoriteAvailable());
        return "cart/list";
    }

    /**
     * 加入购物车。
     *
     * <p>成功后回到商品详情页并提示，让买家可以继续挑别的商品；
     * 达上限或商品不可售时同样回详情页，只是提示换成失败原因。</p>
     */
    @PostMapping("/add")
    public String add(@RequestParam("productId") Long productId,
                      @RequestParam(value = "quantity", required = false) Integer quantity) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/products/" + productId);
        }
        try {
            Cart row = cartService.add(buyer.getId(), productId, quantity);
            return "redirect:/products/" + productId
                    + "?msg=" + encode("已加入购物车，当前共 " + row.getQuantity() + " 件");
        } catch (BusinessException e) {
            return "redirect:/products/" + productId + "?error=" + encode(e.getMessage());
        }
    }

    /** 改数量 */
    @PostMapping("/{id}/quantity")
    public String updateQuantity(@PathVariable("id") Long id,
                                 @RequestParam("quantity") Integer quantity) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/cart");
        }
        try {
            cartService.updateQuantity(buyer.getId(), id, quantity);
            return "redirect:/cart?msg=" + encode("数量已更新");
        } catch (BusinessException e) {
            return "redirect:/cart?error=" + encode(e.getMessage());
        }
    }

    /** 删除单条 */
    @PostMapping("/{id}/remove")
    public String remove(@PathVariable("id") Long id) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/cart");
        }
        try {
            cartService.remove(buyer.getId(), id);
            return "redirect:/cart?msg=" + encode("已从购物车移除");
        } catch (BusinessException e) {
            return "redirect:/cart?error=" + encode(e.getMessage());
        }
    }

    /**
     * 结算：选中条目 → 订单。
     *
     * <p>部分缺货时<b>回到购物车</b>而不是跳「我的订单」：买家需要看到哪几件没下成、
     * 车里的那一行还在，才能改数量重试。全部成功才跳订单页。</p>
     *
     * @param cartIds 勾选的购物车条目 id；不传表示整车结算
     */
    @PostMapping("/checkout")
    public String checkout(@RequestParam(value = "cartIds", required = false) List<Long> cartIds) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/cart");
        }
        try {
            OrderCreatePort.Result result = cartService.checkout(buyer.getId(), cartIds);
            String success = "下单成功，订单号 " + result.orderNo();
            if (result.partial()) {
                return "redirect:/cart?msg=" + encode(
                        success + "；以下商品没下成，已留在购物车：" + result.shortageText());
            }
            return "redirect:/my/orders?msg=" + encode(success);
        } catch (BusinessException e) {
            return "redirect:/cart?error=" + encode(e.getMessage());
        }
    }

    /** 选中条目转为收藏 */
    @PostMapping("/to-favorites")
    public String toFavorites(@RequestParam(value = "cartIds", required = false) List<Long> cartIds) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/cart");
        }
        try {
            FavoriteMoveResult moved = cartService.moveToFavorites(buyer.getId(), cartIds);
            // 两个数分开报：移出的是勾选行，收藏侧可能因「已收藏」而幂等跳过
            String msg = moved.allAlreadyFavorited()
                    ? "已移出购物车 " + moved.movedOut() + " 件，这些商品此前已收藏"
                    : "已移出购物车 " + moved.movedOut() + " 件，收藏新增 " + moved.favoriteAdded() + " 件";
            return "redirect:/cart?msg=" + encode(msg);
        } catch (BusinessException e) {
            return "redirect:/cart?error=" + encode(e.getMessage());
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 未登录：跳买家登录页，并把原地址带回去 */
    private String redirectToLogin(String target) {
        return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode(target);
    }

    /** 中文提示进 URL 前统一编码，避免 + 与 & 把参数截断 */
    private String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}

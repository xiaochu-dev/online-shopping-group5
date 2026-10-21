package com.group5.shop.portal.controller;

import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.contract.BuyerContext;
import com.group5.shop.portal.contract.BuyerProfile;
import com.group5.shop.portal.domain.ProductView;
import com.group5.shop.portal.service.PortalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 买家门户控制器（T2-13 ~ T2-16）。
 *
 * <table border="1">
 *   <caption>门户路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>登录</th><th>功能</th></tr>
 *   <tr><td>GET</td><td>/</td><td>否</td><td>商品列表（分页 + 两级分类 + 搜索）</td></tr>
 *   <tr><td>GET</td><td>/products/{id}</td><td>否</td><td>商品详情（多图 / 富媒体 / 库存）</td></tr>
 *   <tr><td>POST</td><td>/products/{id}/intents</td><td><b>是</b></td><td>提交购买意向</td></tr>
 *   <tr><td>POST</td><td>/intents/cancel</td><td>否</td><td>基线条目兜底：凭口令码撤销（页面已无入口）</td></tr>
 * </table>
 *
 * <p><b>浏览公开、下单强制登录</b>（A.2.1 澄清结论）：未登录点下单会跳买家登录页并带回跳地址，
 * 登录后回到原商品页继续下单，不用重新找商品。</p>
 *
 * @author 周到
 */
@Controller
public class PortalController {

    /**
     * 买家登录页路径（与账号与客户模块约定的入口）。
     *
     * <p>若账号模块最终采用别的路径，<b>只改这一处</b>：门户里所有「跳登录」都走这里。</p>
     */
    private static final String BUYER_LOGIN_PATH = "/buyer/login";

    private final PortalService portalService;
    private final BuyerContext buyerContext;

    public PortalController(PortalService portalService, BuyerContext buyerContext) {
        this.portalService = portalService;
        this.buyerContext = buyerContext;
    }

    // ==================================================================
    // B-1 商品列表
    // ==================================================================

    @GetMapping("/")
    public String productList(@RequestParam(value = "page", required = false) Integer page,
                              @RequestParam(value = "size", required = false) Integer size,
                              @RequestParam(value = "categoryId", required = false) Long categoryId,
                              @RequestParam(value = "keyword", required = false) String keyword,
                              @RequestParam(value = "msg", required = false) String msg,
                              @RequestParam(value = "error", required = false) String error,
                              Model model) {
        PageResult<Product> pageResult =
                portalService.listOnSaleProducts(page, size, categoryId, keyword);

        model.addAttribute("pageResult", pageResult);
        model.addAttribute("categoryTree", portalService.categoryTree());
        model.addAttribute("categoryNames", portalService.categoryNames());
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("buyerName", currentBuyerName());
        return "portal/product-list";
    }

    // ==================================================================
    // B-2 商品详情
    // ==================================================================

    /** 商品详情页。商品不存在或已下架时由 {@code GlobalExceptionHandler} 渲染错误页 */
    @GetMapping("/products/{id}")
    public String productDetail(@PathVariable("id") Long id,
                                @RequestParam(value = "msg", required = false) String msg,
                                @RequestParam(value = "error", required = false) String error,
                                Model model) {
        ProductView view = portalService.productDetail(id);
        model.addAttribute("view", view);
        model.addAttribute("product", view.product());
        model.addAttribute("queueSize", portalService.queueSize(id));
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        model.addAttribute("loggedIn", buyerContext.currentId() != null);
        model.addAttribute("buyerName", currentBuyerName());
        return "portal/product-detail";
    }

    // ==================================================================
    // B-3 提交意向（必须登录）
    // ==================================================================

    /**
     * 提交购买意向：未登录先跳登录页，登录后就地提交。
     *
     * <p>成功与失败都用 303 回商品详情页（Post/Redirect/Get），
     * 避免卖家把 F5 当成「再提交一次」。</p>
     */
    @PostMapping("/products/{id}/intents")
    public String submitIntent(@PathVariable("id") Long id,
                               @RequestParam(value = "tradeAddress", required = false) String tradeAddress) {
        Long buyerId = buyerContext.currentId();
        if (buyerId == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/products/" + id);
        }

        BuyerProfile profile = buyerContext.profile(buyerId);
        if (profile == null) {
            // 会话里有 id 但买家资料读不到（账号被删/表未就绪）：当作未登录处理
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/products/" + id);
        }

        try {
            portalService.submitOrder(buyerId, profile, id, tradeAddress);
            return "redirect:/products/" + id + "?msg="
                    + encode("购买意向已提交，等待卖家确认。可在「我的订单」查看已有结果的订单。");
        } catch (BusinessException e) {
            return "redirect:/products/" + id + "?error=" + encode(e.getMessage());
        }
    }

    // ==================================================================
    // 基线条目兜底：凭口令码撤销
    // ==================================================================

    /**
     * 凭口令码撤销（基线 FR-04 的验收物）。
     *
     * <p>A 之后客户不再自助取消，<b>买家页面已经没有这个入口</b>；
     * 保留接口是为了让迭代 1 / 迭代 2 基线的验收材料仍然可复现（口径：标注迭代 3 移除）。</p>
     */
    @PostMapping("/intents/cancel")
    public String cancelIntent(@RequestParam(value = "tokenCode", required = false) String tokenCode) {
        boolean ok = portalService.cancelByTokenCode(tokenCode);
        if (ok) {
            return "redirect:/?msg=" + encode("口令码对应的意向已撤销，队列中其余买家自动前移");
        }
        return "redirect:/?error=" + encode("口令码无效，或该意向不可撤销（仅排队中的意向可撤销）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 顶栏显示「你好，xxx」；未登录返回 null */
    private String currentBuyerName() {
        Long buyerId = buyerContext.currentId();
        if (buyerId == null) {
            return null;
        }
        BuyerProfile profile = buyerContext.profile(buyerId);
        return profile == null ? null : profile.username();
    }

    /** 中文提示参数必须按 UTF-8 编码，否则在重定向 URL 里会变成乱码或被截断 */
    private String encode(String text) {
        return UriUtils.encode(text, StandardCharsets.UTF_8);
    }
}

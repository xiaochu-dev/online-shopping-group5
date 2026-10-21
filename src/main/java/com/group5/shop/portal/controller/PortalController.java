package com.group5.shop.portal.controller;

import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.exception.BusinessException;
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
 * 买家门户控制器（T2-11 ~ T2-14）。
 *
 * <p><b>本模块全部路由公开，不经过登录拦截器</b>（基线需求 FR-03：买家免注册免登录）。
 * 拦截器只注册在 {@code /admin} 与 {@code /admin/**} 上，因此这里不需要任何权限判断。</p>
 *
 * <table border="1">
 *   <caption>门户路由（对应 docs/API契约-迭代2.md 第 4 节）</caption>
 *   <tr><th>编号</th><th>方法</th><th>路径</th><th>功能</th><th>响应</th></tr>
 *   <tr><td>B-1</td><td>GET</td><td>/</td><td>商品列表（分页 + 分类 + 搜索）</td><td>200 portal/product-list</td></tr>
 *   <tr><td>B-2</td><td>GET</td><td>/products/{id}</td><td>商品详情</td><td>200 portal/product-detail</td></tr>
 *   <tr><td>B-3</td><td>POST</td><td>/products/{id}/intents</td><td>提交购买意向</td><td>200 渲染详情页并带口令码</td></tr>
 *   <tr><td>B-4</td><td>POST</td><td>/intents/cancel</td><td>凭口令码撤销</td><td>303 回首页 + msg/error</td></tr>
 * </table>
 *
 * <p><b>为什么 B-3 不重定向</b>：口令码只在这一次响应里展示。
 * 若改用 303 把口令码放到 {@code ?token=} 上，它会进入浏览器历史、服务器访问日志和 Referer 头，
 * 等于公开了唯一的撤销凭据。所以这里直接渲染详情页，让码只出现在响应正文里（FR-03-8「当场一次性显示」）。</p>
 *
 * @author 周到
 */
@Controller
public class PortalController {

    private final PortalService portalService;

    public PortalController(PortalService portalService) {
        this.portalService = portalService;
    }

    // ==================================================================
    // B-1 商品列表
    // ==================================================================

    /**
     * 买家首页：在售商品列表，支持分页、分类筛选、关键词搜索。
     *
     * @param page       页码，从 1 开始
     * @param size       每页条数，默认 12
     * @param categoryId 分类筛选，不传为全部
     * @param keyword    关键词，匹配名称或描述
     * @param msg        操作成功提示（撤销成功后回跳时携带）
     * @param error      操作失败提示
     */
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
        model.addAttribute("categories", portalService.listEnabledCategories());
        model.addAttribute("categoryNames", portalService.listCategoryNames());
        model.addAttribute("categoryId", categoryId);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("msg", msg);
        model.addAttribute("error", error);
        return "portal/product-list";
    }

    // ==================================================================
    // B-2 商品详情
    // ==================================================================

    /** 商品详情页。商品不存在或已下架时由 {@code GlobalExceptionHandler} 渲染错误页 */
    @GetMapping("/products/{id}")
    public String productDetail(@PathVariable("id") Long id, Model model) {
        Product product = portalService.requireOnSaleProduct(id);
        model.addAttribute("product", product);
        model.addAttribute("queueSize", portalService.queueSize(id));
        return "portal/product-detail";
    }

    // ==================================================================
    // B-3 提交意向
    // ==================================================================

    /**
     * 提交购买意向，成功后就地渲染详情页并展示口令码。
     *
     * <p>失败不重定向，回渲染详情页并在 {@code error} 中给出原因，
     * 这样买家填的姓名电话还在，不用重打一遍。</p>
     */
    @PostMapping("/products/{id}/intents")
    public String submitIntent(@PathVariable("id") Long id,
                               @RequestParam(value = "buyerName", required = false) String buyerName,
                               @RequestParam(value = "phone", required = false) String phone,
                               Model model) {
        Product product = portalService.requireOnSaleProduct(id);
        model.addAttribute("product", product);

        try {
            model.addAttribute("tokenCode", portalService.submitIntent(id, buyerName, phone).getTokenCode());
            model.addAttribute("buyerName", buyerName == null ? "" : buyerName.trim());
            model.addAttribute("queueSize", portalService.queueSize(id));
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("buyerName", buyerName == null ? "" : buyerName.trim());
            model.addAttribute("phone", phone == null ? "" : phone.trim());
            model.addAttribute("queueSize", portalService.queueSize(id));
        }
        return "portal/product-detail";
    }

    // ==================================================================
    // B-4 撤销意向
    // ==================================================================

    /**
     * 凭口令码撤销意向，然后 303 回首页（Post/Redirect/Get，避免 F5 重复提交）。
     *
     * <p>成功与失败都重定向，只带不同的提示参数；失败提示对「码不存在」和「状态不允许」
     * 完全一致，不泄露码是否真实存在。</p>
     */
    @PostMapping("/intents/cancel")
    public String cancelIntent(@RequestParam(value = "tokenCode", required = false) String tokenCode) {
        boolean ok = portalService.cancelByTokenCode(tokenCode);
        if (ok) {
            return "redirect:/?msg=" + encode("口令码对应的意向已撤销，队列中其余买家自动前移");
        }
        return "redirect:/?error=" + encode("口令码无效，或该意向不可撤销（仅排队中的意向可撤销）");
    }

    /** 中文提示参数必须按 UTF-8 编码，否则在重定向 URL 里会变成乱码或被截断 */
    private String encode(String text) {
        return UriUtils.encode(text, StandardCharsets.UTF_8);
    }
}

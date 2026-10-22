package com.group5.shop.admin.controller;

import com.group5.shop.admin.port.IntentHistoryPort;
import com.group5.shop.admin.port.IntentHistoryItem;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.service.BuyerAuthService;
import com.group5.shop.common.api.PageResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 卖家后台「客户管理」（T2-04 / T2-05，挑战需求 A.2.3）。
 *
 * <table border="1">
 *   <caption>客户管理路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>权限</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/admin/customers</td><td>需卖家登录</td><td>客户列表：分页 + 用户名/电话检索</td></tr>
 *   <tr><td>GET</td><td>/admin/customers/{id}</td><td>需卖家登录</td><td>客户详情 + 购买历史</td></tr>
 * </table>
 *
 * <p>权限由 {@code LoginInterceptor}（拦 /admin/**）统一保证，本类不再判登录态。</p>
 *
 * <p><b>展示口径</b>（澄清结论）：客户列表固定展示用户名、电话、默认交易地点、注册时间，
 * <b>不做脱敏</b>——这是卖家内部的经营视图，脱敏反而影响联系客户。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class CustomerAdminController {

    /** 默认每页条数 */
    private static final long DEFAULT_SIZE = 10;

    private final BuyerAuthService buyerAuthService;

    /**
     * 购买历史端口：portal 模块（周到）实现后自动注入；
     * 未实现时 {@code getIfAvailable()} 返回 null，页面显示「交易模块尚未接入」。
     */
    private final ObjectProvider<IntentHistoryPort> historyPortProvider;

    public CustomerAdminController(BuyerAuthService buyerAuthService,
                                   ObjectProvider<IntentHistoryPort> historyPortProvider) {
        this.buyerAuthService = buyerAuthService;
        this.historyPortProvider = historyPortProvider;
    }

    /**
     * 客户列表。
     *
     * @param keyword 用户名 / 电话检索词
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @param model   视图模型
     * @return 列表页
     */
    @GetMapping("/admin/customers")
    public String list(@RequestParam(value = "keyword", required = false) String keyword,
                       @RequestParam(value = "page", defaultValue = "1") long page,
                       @RequestParam(value = "size", defaultValue = "10") long size,
                       Model model) {
        PageResult<Buyer> result = buyerAuthService.pageForAdmin(keyword, page, size);
        model.addAttribute("pageResult", result);
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        model.addAttribute("totalCustomers", buyerAuthService.countAll());
        return "admin/customers";
    }

    /**
     * 客户详情 + 购买历史。
     *
     * @param id    客户 id
     * @param model 视图模型
     * @return 详情页；客户不存在时回列表
     */
    @GetMapping("/admin/customers/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        Buyer buyer = buyerAuthService.findById(id);
        if (buyer == null) {
            return "redirect:/admin/customers?error=%E5%AE%A2%E6%88%B7%E4%B8%8D%E5%AD%98%E5%9C%A8";
        }
        IntentHistoryPort port = historyPortProvider.getIfAvailable();
        List<IntentHistoryItem> history = port == null ? List.of() : port.listByBuyer(id);

        model.addAttribute("buyer", buyer);
        model.addAttribute("history", history);
        model.addAttribute("historyReady", port != null);
        return "admin/customer-detail";
    }
}

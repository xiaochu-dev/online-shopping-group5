package com.group5.shop.portal.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.portal.domain.OrderItem;
import com.group5.shop.portal.service.OrderHistoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 「我的订单」控制器（T2-17；迭代 3 增补「进行中」区块与自助撤销）。
 *
 * <p>必须登录：未登录直接跳买家登录页并带回跳地址，登录后原样回到这一页。</p>
 *
 * <p><b>迭代 3 的口径变更</b>：原设计只展示已有结果的单（成功 / 失败），客户提交后
 * 进入黑箱——查不到自己提交的意向，也无法反悔。迭代 3 补上两块：</p>
 * <ul>
 *   <li><b>进行中</b>：排队中 + 交易中，单独成块、不分页，按提交时间正序；</li>
 *   <li><b>自助撤销</b>：仅「排队中」可撤销，撤销后自动退出队列，后面的人前移。</li>
 * </ul>
 *
 * @author 周到
 */
@Controller
public class MyOrdersController {

    /** 买家登录页路径，与 {@code PortalController} 保持同一约定 */
    private static final String BUYER_LOGIN_PATH = "/buyer/login";

    private final OrderHistoryService orderHistoryService;
    private final BuyerContext buyerContext;

    public MyOrdersController(OrderHistoryService orderHistoryService, BuyerContext buyerContext) {
        this.orderHistoryService = orderHistoryService;
        this.buyerContext = buyerContext;
    }

    /**
     * 我的订单列表：上半区「进行中」，下半区「已结束」（分页）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，默认 10
     */
    @GetMapping("/my/orders")
    public String list(@RequestParam(value = "page", required = false) Integer page,
                       @RequestParam(value = "size", required = false) Integer size,
                       Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/my/orders");
        }

        PageResult<OrderItem> result = orderHistoryService.list(buyer.getId(), page, size);
        List<OrderItem> active = orderHistoryService.activeList(buyer.getId());
        model.addAttribute("pageResult", result);
        model.addAttribute("activeIntents", active);
        model.addAttribute("buyerName", buyer.getUsername());
        return "portal/my-orders";
    }

    /**
     * 撤销自己的意向（迭代 3 新增）。
     *
     * <p>合法性由 {@link OrderHistoryService#cancel} 在 SQL 层原子判定（本人 + 仅排队中），
     * 失败时不区分「不存在」与「不可撤销」——避免用返回差异探测别人的单号。</p>
     *
     * @param id 意向 id
     */
    @PostMapping("/my/orders/{id}/cancel")
    public String cancel(@PathVariable("id") Long id) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return "redirect:" + BUYER_LOGIN_PATH + "?redirect=" + encode("/my/orders");
        }

        boolean ok = orderHistoryService.cancel(buyer.getId(), id);
        if (ok) {
            return "redirect:/my/orders?msg=" + encode("意向已撤销，已退出排队队列");
        }
        return "redirect:/my/orders?error=" + encode("撤销失败：该意向不存在，或当前状态不可撤销（仅排队中的意向可撤销）");
    }

    private String encode(String text) {
        return UriUtils.encode(text, StandardCharsets.UTF_8);
    }
}

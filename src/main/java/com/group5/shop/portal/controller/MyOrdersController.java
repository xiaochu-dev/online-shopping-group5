package com.group5.shop.portal.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.portal.domain.OrderItem;
import com.group5.shop.portal.service.OrderHistoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 「我的订单」控制器（T2-17）。
 *
 * <p>必须登录：未登录直接跳买家登录页并带回跳地址，登录后原样回到这一页。</p>
 *
 * <p>列表只含<b>已有结果</b>的单（成功 / 失败）。排队中与交易中的单不进这个列表——
 * 客户看不到位次、也不能自助取消，这是澄清结论里明确的取舍，下单成功页会补一句
 * 「已提交，等待卖家确认」。</p>
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
     * 我的订单列表。
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
        model.addAttribute("pageResult", result);
        model.addAttribute("buyerName", buyer.getUsername());
        return "portal/my-orders";
    }

    private String encode(String text) {
        return UriUtils.encode(text, StandardCharsets.UTF_8);
    }
}

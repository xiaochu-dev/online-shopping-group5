package com.group5.shop.shipping.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.shipping.domain.BuyerAddress;
import com.group5.shop.shipping.service.AddressService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 收货地址簿页面（挑战需求 C.2.3，owner：胡成溯）。
 *
 * <table border="1">
 *   <caption>地址路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/my/addresses</td><td>地址列表 + 新增表单</td></tr>
 *   <tr><td>POST</td><td>/my/addresses/add</td><td>新增（首条自动设为默认）</td></tr>
 *   <tr><td>POST</td><td>/my/addresses/{id}/update</td><td>修改内容</td></tr>
 *   <tr><td>POST</td><td>/my/addresses/{id}/default</td><td>设为默认</td></tr>
 *   <tr><td>POST</td><td>/my/addresses/{id}/delete</td><td>删除（默认地址被删会自动递补）</td></tr>
 * </table>
 *
 * <p>权限由买家拦截器（{@code /my/**}）统一保证，本类不再判登录态。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class AddressController {

    private final AddressService addressService;
    private final BuyerContext buyerContext;

    public AddressController(AddressService addressService, BuyerContext buyerContext) {
        this.addressService = addressService;
        this.buyerContext = buyerContext;
    }

    /**
     * 地址列表。
     *
     * @param model 视图模型
     * @return 模板名
     */
    @GetMapping("/my/addresses")
    public String list(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/addresses");
        }
        List<BuyerAddress> addresses = addressService.list(buyer.getId());
        model.addAttribute("addresses", addresses);
        model.addAttribute("buyerName", buyer.getUsername());
        model.addAttribute("defaultAddressText", addressService.defaultAddressText(buyer.getId()));
        return "shipping/addresses";
    }

    /**
     * 新增地址。
     *
     * @param receiver  收货人
     * @param phone     电话
     * @param region    省市区
     * @param detail    详细地址
     * @param asDefault 是否设为默认
     * @return 重定向回列表
     */
    @PostMapping("/my/addresses/add")
    public String add(@RequestParam("receiver") String receiver,
                      @RequestParam("phone") String phone,
                      @RequestParam("region") String region,
                      @RequestParam("detail") String detail,
                      @RequestParam(value = "asDefault", required = false) String asDefault) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/addresses");
        }
        try {
            BuyerAddress created = addressService.create(buyer.getId(), receiver, phone, region, detail,
                    asDefault != null);
            String msg = created.defaulted()
                    ? "已保存并设为默认地址，下单将使用该地址"
                    : "已保存地址";
            return "redirect:/my/addresses?msg=" + encode(msg);
        } catch (BusinessException e) {
            return "redirect:/my/addresses?error=" + encode(e.getMessage());
        }
    }

    /**
     * 修改地址。
     *
     * @param id       地址主键
     * @param receiver 收货人
     * @param phone    电话
     * @param region   省市区
     * @param detail   详细地址
     * @return 重定向回列表
     */
    @PostMapping("/my/addresses/{id}/update")
    public String update(@PathVariable("id") Long id,
                         @RequestParam("receiver") String receiver,
                         @RequestParam("phone") String phone,
                         @RequestParam("region") String region,
                         @RequestParam("detail") String detail) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/addresses");
        }
        try {
            addressService.update(buyer.getId(), id, receiver, phone, region, detail);
            return "redirect:/my/addresses?msg=" + encode("地址已更新");
        } catch (BusinessException e) {
            return "redirect:/my/addresses?error=" + encode(e.getMessage());
        }
    }

    /**
     * 设为默认地址。
     *
     * @param id 地址主键
     * @return 重定向回列表
     */
    @PostMapping("/my/addresses/{id}/default")
    public String setDefault(@PathVariable("id") Long id) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/addresses");
        }
        try {
            addressService.setDefault(buyer.getId(), id);
            return "redirect:/my/addresses?msg=" + encode("已设为默认地址，下单将使用该地址");
        } catch (BusinessException e) {
            return "redirect:/my/addresses?error=" + encode(e.getMessage());
        }
    }

    /**
     * 删除地址。
     *
     * @param id 地址主键
     * @return 重定向回列表
     */
    @PostMapping("/my/addresses/{id}/delete")
    public String delete(@PathVariable("id") Long id) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/addresses");
        }
        try {
            addressService.delete(buyer.getId(), id);
            return "redirect:/my/addresses?msg=" + encode("地址已删除");
        } catch (BusinessException e) {
            return "redirect:/my/addresses?error=" + encode(e.getMessage());
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private String redirectToLogin(String target) {
        return "redirect:/buyer/login?redirect=" + encode(target);
    }

    private String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

package com.group5.shop.shipping.controller;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.contract.SellerContext;
import com.group5.shop.shipping.domain.Carrier;
import com.group5.shop.shipping.service.ShipmentService;
import com.group5.shop.shipping.view.ShipmentViews;
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
 * 卖家发货管理（挑战需求 C.2.1 / C.2.2，owner：胡成溯）。
 *
 * <table border="1">
 *   <caption>发货路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/admin/shipments</td><td>待发货订单（状态＝备货完成）</td></tr>
 *   <tr><td>POST</td><td>/admin/shipments/{intentId}/ship</td><td>选择承运商 + 填运单号，登记发货</td></tr>
 * </table>
 *
 * <p><b>为什么只列「备货完成」的订单</b>：按订单状态机，发货是备货之后的下一步；
 * 允许跳级会让「先发货后确认」变成可能，状态机就失去意义。</p>
 *
 * <p>权限由卖家拦截器（{@code /admin/**}）统一保证，本类不再判登录态。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class AdminShipmentController {

    private final ShipmentService shipmentService;
    private final SellerContext sellerContext;

    public AdminShipmentController(ShipmentService shipmentService, SellerContext sellerContext) {
        this.shipmentService = shipmentService;
        this.sellerContext = sellerContext;
    }

    /**
     * 待发货列表。
     *
     * @param model 视图模型
     * @return 模板名
     */
    @GetMapping("/admin/shipments")
    public String list(Model model) {
        Long sellerId = sellerContext.currentId();
        List<ShipmentViews.Pending> rows = shipmentService.pendingForSeller(sellerId).stream()
                .map(ShipmentViews.Pending::new).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("carriers", Carrier.ALL);
        model.addAttribute("sellerName", sellerContext.currentName());
        model.addAttribute("orderServiceAvailable", shipmentService.orderServiceAvailable());
        return "shipping/admin-shipments";
    }

    /**
     * 登记发货。
     *
     * @param intentId   订单主键
     * @param carrier    承运商代码
     * @param trackingNo 运单号
     * @return 重定向回待发货列表
     */
    @PostMapping("/admin/shipments/{intentId}/ship")
    public String ship(@PathVariable("intentId") Long intentId,
                       @RequestParam("carrier") String carrier,
                       @RequestParam("trackingNo") String trackingNo) {
        Long sellerId = sellerContext.currentId();
        try {
            shipmentService.ship(intentId, sellerId, carrier, trackingNo);
            return "redirect:/admin/shipments?msg=" + encode("已发货，运单号 " + trackingNo.trim());
        } catch (BusinessException e) {
            return "redirect:/admin/shipments?error=" + encode(e.getMessage());
        }
    }

    private String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

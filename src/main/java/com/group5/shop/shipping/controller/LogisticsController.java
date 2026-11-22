package com.group5.shop.shipping.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.shipping.domain.Shipment;
import com.group5.shop.shipping.service.ShipmentService;
import com.group5.shop.shipping.service.ShipmentService.ShipmentView;
import com.group5.shop.shipping.service.TrackingService;
import com.group5.shop.shipping.view.ShipmentViews;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 买家物流查询（挑战需求 C.2.4，owner：胡成溯）。
 *
 * <table border="1">
 *   <caption>物流路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/my/logistics</td><td>已发货订单列表 + 当前物流状态</td></tr>
 *   <tr><td>GET</td><td>/my/logistics/{intentId}</td><td>单笔订单的物流轨迹</td></tr>
 * </table>
 *
 * <p><b>只能查自己的</b>：列表按登录买家过滤，详情页走
 * {@link ShipmentService#detailForBuyer} 再校验一次归属——拿到别人的订单号也查不出来。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class LogisticsController {

    private final ShipmentService shipmentService;
    private final TrackingService trackingService;
    private final BuyerContext buyerContext;

    public LogisticsController(ShipmentService shipmentService,
                               TrackingService trackingService,
                               BuyerContext buyerContext) {
        this.shipmentService = shipmentService;
        this.trackingService = trackingService;
        this.buyerContext = buyerContext;
    }

    /**
     * 物流列表。
     *
     * @param model 视图模型
     * @return 模板名
     */
    @GetMapping("/my/logistics")
    public String list(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/logistics");
        }
        List<ShipmentViews.Row> rows = shipmentService.forBuyer(buyer.getId()).stream()
                .map(ShipmentViews.Row::new).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("buyerName", buyer.getUsername());
        return "shipping/logistics";
    }

    /**
     * 物流详情（轨迹）。
     *
     * @param intentId 订单主键
     * @param model    视图模型
     * @return 模板名
     */
    @GetMapping("/my/logistics/{intentId}")
    public String detail(@PathVariable("intentId") Long intentId, Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            return redirectToLogin("/my/logistics");
        }
        try {
            ShipmentView view = shipmentService.detailForBuyer(buyer.getId(), intentId);
            Shipment shipment = view.shipment();
            model.addAttribute("detail", new ShipmentViews.Detail(view));
            model.addAttribute("events", trackingService.timeline(shipment, TimeUtils.nowMillis())
                    .stream().map(ShipmentViews.Event::new).toList());
            model.addAttribute("buyerName", buyer.getUsername());
            return "shipping/logistics-detail";
        } catch (BusinessException e) {
            return "redirect:/my/logistics?error=" + encode(e.getMessage());
        }
    }

    private String redirectToLogin(String target) {
        return "redirect:/buyer/login?redirect=" + encode(target);
    }

    private String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}

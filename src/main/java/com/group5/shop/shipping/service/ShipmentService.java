package com.group5.shop.shipping.service;

import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.shipping.domain.Carrier;
import com.group5.shop.shipping.domain.Shipment;
import com.group5.shop.shipping.domain.ShipmentMapper;
import com.group5.shop.shipping.port.OrderShipPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发货与物流查询（挑战需求 C.2.1 / C.2.2 / C.2.4，owner：胡成溯）。
 *
 * <p><b>一次发货做两件事，且在同一事务里</b>：① 把订单推进到「已发货」（通过端口交给订单域）；
 * ② 写入承运商与运单号。任一步失败整体回滚——不会出现「订单显示已发货、却查不到单号」。</p>
 *
 * <p><b>重复发货由库层拦住</b>：{@code shipment.intent_id} 唯一，加上单据状态必须是
 * 「备货完成」，两个口子任一生效都不会产生第二条发货记录。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class ShipmentService {

    /** 运单号长度下限 */
    private static final int MIN_TRACKING = 6;
    /** 运单号长度上限 */
    private static final int MAX_TRACKING = 32;

    /** 发货视图：订单摘要 + 发货记录 + 当前物流状态 */
    public record ShipmentView(OrderShipPort.OrderBrief order,
                               Shipment shipment,
                               String trackingStatus,
                               TrackingService.TrackingEvent latest) {
    }

    private final ObjectProvider<OrderShipPort> orderPort;
    private final ShipmentMapper shipmentMapper;
    private final TrackingService trackingService;

    public ShipmentService(ObjectProvider<OrderShipPort> orderPort,
                           ShipmentMapper shipmentMapper,
                           TrackingService trackingService) {
        this.orderPort = orderPort;
        this.shipmentMapper = shipmentMapper;
        this.trackingService = trackingService;
    }

    /**
     * 订单服务是否已接入（未接入时发货页给出提示，不报 500）。
     *
     * @return true 表示可发货
     */
    public boolean orderServiceAvailable() {
        return orderPort.getIfAvailable() != null;
    }

    /**
     * 待发货订单：当前卖家名下、状态为「备货完成」的订单。
     *
     * @param sellerId 卖家主键
     * @return 待发货列表；订单服务未接入时返回空列表
     */
    public List<ShipmentView> pendingForSeller(Long sellerId) {
        OrderShipPort port = orderPort.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        List<ShipmentView> result = new ArrayList<>();
        for (OrderShipPort.OrderBrief order : port.findBySeller(sellerId, IntentStatus.PREPARED)) {
            result.add(new ShipmentView(order, shipmentMapper.selectByIntent(order.intentId()), null, null));
        }
        return result;
    }

    /**
     * 登记发货：校验承运商与运单号 → 推进订单状态 → 写发货记录。
     *
     * @param intentId   订单主键
     * @param sellerId   卖家主键
     * @param carrier    承运商代码
     * @param trackingNo 运单号
     * @return 写入的发货记录
     */
    @Transactional
    public Shipment ship(Long intentId, Long sellerId, String carrier, String trackingNo) {
        OrderShipPort port = orderPort.getIfAvailable();
        if (port == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "订单服务尚未接入：等待订单模块实现 OrderShipPort 后自动生效");
        }
        String carrierCode = Carrier.require(carrier);
        String no = checkedTracking(trackingNo);

        if (shipmentMapper.selectByIntent(intentId) != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该订单已登记过发货，不能重复发货");
        }

        // 先推进状态：状态不对会在这里抛出，不会留下半条发货记录
        port.markShipped(intentId, sellerId);

        long now = TimeUtils.nowMillis();
        Shipment shipment = new Shipment();
        shipment.setIntentId(intentId);
        shipment.setCarrier(carrierCode);
        shipment.setTrackingNo(no);
        shipment.setShippedAt(now);
        shipment.setCreatedAt(now);
        shipmentMapper.insert(shipment);
        return shipment;
    }

    /**
     * 买家视角的发货列表：只保留已产生发货记录的订单，按发货时间倒序。
     *
     * @param buyerId 买家主键
     * @return 物流列表
     */
    public List<ShipmentView> forBuyer(Long buyerId) {
        OrderShipPort port = orderPort.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        List<OrderShipPort.OrderBrief> orders = port.findByBuyer(buyerId, null);
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> ids = orders.stream().map(OrderShipPort.OrderBrief::intentId).toList();
        Map<Long, Shipment> byIntent = new HashMap<>();
        for (Shipment shipment : shipmentMapper.selectByIntents(ids)) {
            byIntent.put(shipment.getIntentId(), shipment);
        }
        long now = TimeUtils.nowMillis();
        List<ShipmentView> result = new ArrayList<>();
        for (OrderShipPort.OrderBrief order : orders) {
            Shipment shipment = byIntent.get(order.intentId());
            if (shipment == null) {
                continue;
            }
            result.add(new ShipmentView(order, shipment,
                    trackingService.statusText(shipment, now),
                    trackingService.latest(shipment, now)));
        }
        result.sort((a, b) -> Long.compare(b.shipment().getShippedAt(), a.shipment().getShippedAt()));
        return result;
    }

    /**
     * 买家查单条物流：校验订单归属，避免用别人的订单号探测。
     *
     * @param buyerId  买家主键
     * @param intentId 订单主键
     * @return 该订单的物流视图
     * @throws BusinessException 订单不属于该买家或尚未发货
     */
    public ShipmentView detailForBuyer(Long buyerId, Long intentId) {
        OrderShipPort port = orderPort.getIfAvailable();
        if (port == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单服务尚未接入，暂时无法查询物流");
        }
        OrderShipPort.OrderBrief order = port.findByBuyer(buyerId, null).stream()
                .filter(item -> item.intentId().equals(intentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前账号"));
        Shipment shipment = shipmentMapper.selectByIntent(intentId);
        if (shipment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该订单尚未发货");
        }
        long now = TimeUtils.nowMillis();
        return new ShipmentView(order, shipment,
                trackingService.statusText(shipment, now),
                trackingService.latest(shipment, now));
    }

    /**
     * 某订单的发货记录（无则空）。
     *
     * @param intentId 订单主键
     * @return 发货记录
     */
    public Optional<Shipment> ofIntent(Long intentId) {
        return Optional.ofNullable(shipmentMapper.selectByIntent(intentId));
    }

    private String checkedTracking(String trackingNo) {
        String no = trackingNo == null ? "" : trackingNo.trim();
        if (no.length() < MIN_TRACKING || no.length() > MAX_TRACKING) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "运单号长度需在 " + MIN_TRACKING + " ~ " + MAX_TRACKING + " 位之间");
        }
        if (!no.matches("[A-Za-z0-9-]+")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "运单号只能包含字母、数字和短横线");
        }
        return no;
    }
}

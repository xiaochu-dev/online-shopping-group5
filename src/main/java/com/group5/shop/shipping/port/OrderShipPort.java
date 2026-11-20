package com.group5.shop.shipping.port;

import java.util.List;

/**
 * 订单侧只读与发货推进端口（挑战需求 C.2，消费方：配送模块；提供方：订单域·周到）。
 *
 * <p><b>为什么需要这个端口</b>：配送模块要展示「待发货订单」、并把订单推进到「已发货」，
 * 但 {@code intent}、{@code order_item} 属订单域。按项目既有做法——<b>消费方定义、提供方实现</b>——
 * 这里只声明「我需要什么」，订单域用适配器实现，配送模块不 import 订单实体、不直接查表。</p>
 *
 * <p><b>降级策略</b>：实现缺席时（{@code ObjectProvider} 取不到），卖家发货页显示
 * 「订单服务尚未接入」，买家物流页照常展示既有发货记录，不影响配送模块自身数据。</p>
 *
 * @author 胡成溯（组长）
 */
public interface OrderShipPort {

    /**
     * 订单摘要（配送模块需要的字段集合，刻意不暴露订单实体）。
     *
     * @param intentId  订单主键
     * @param orderNo   订单号（页面展示）
     * @param buyerId   买家主键
     * @param buyerName 买家用户名
     * @param phone     联系电话
     * @param address   交易地点（下单时的收货地址快照）
     * @param status    订单状态
     * @param createdAt 下单时间（毫秒）
     * @param itemsText 商品明细摘要，形如「机械键盘 ×1、台灯 ×2」
     * @param shippable 是否处于「备货完成、可发货」状态
     */
    record OrderBrief(Long intentId,
                      String orderNo,
                      Long buyerId,
                      String buyerName,
                      String phone,
                      String address,
                      String status,
                      long createdAt,
                      String itemsText,
                      boolean shippable) {
    }

    /**
     * 某卖家在指定状态下的订单（发货页用，按时间倒序）。
     *
     * @param sellerId 卖家主键
     * @param status   订单状态，如 {@code prepared}
     * @return 订单摘要列表，无数据返回空列表
     */
    List<OrderBrief> findBySeller(Long sellerId, String status);

    /**
     * 某买家在指定状态（或全部状态，传 null）下的订单，物流页用。
     *
     * @param buyerId 买家主键
     * @param status  订单状态，null 表示不限
     * @return 订单摘要列表，按时间倒序
     */
    List<OrderBrief> findByBuyer(Long buyerId, String status);

    /**
     * 取某卖家名下的单张订单，用于发货前校验归属。
     *
     * @param intentId 订单主键
     * @param sellerId 卖家主键
     * @return 订单摘要
     * @throws com.group5.shop.common.exception.BusinessException 订单不存在或不属于该卖家
     */
    OrderBrief findOwned(Long intentId, Long sellerId);

    /**
     * 把订单推进到「已发货」。
     *
     * <p>实现方必须校验：订单属于该卖家、当前状态为「备货完成」。</p>
     *
     * @param intentId 订单主键
     * @param sellerId 卖家主键
     */
    void markShipped(Long intentId, Long sellerId);
}

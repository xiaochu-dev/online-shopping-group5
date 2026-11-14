package com.group5.shop.cart.port;

import java.util.List;

/**
 * 下单端口（挑战需求 B.2.3，消费方：购物车；提供方：订单域 / 周到）。
 *
 * <p><b>为什么由购物车定义接口</b>：按团队既有的「消费方定义端口、提供方实现」约定
 * （参见 {@code admin/port/IntentHistoryPort}），购物车只声明「我需要一个能按明细下单的
 * 能力」，具体怎么生成订单、怎么扣库存、怎么落五位状态机，全是订单域的实现细节。
 * 购物车不 import 订单域的任何类，因此两边可以并行开发、互不看对方代码。</p>
 *
 * <p><b>提供方尚未实现时的行为</b>：{@link com.group5.shop.cart.service.CartService}
 * 用 {@code ObjectProvider} 取实现，取不到时结算按钮给出「下单功能尚未接入」提示，
 * 购物车其余功能（加购、改数量、删除）不受影响。</p>
 *
 * @author 胡成溯（组长）
 */
public interface OrderCreatePort {

    /**
     * 按购物车明细创建订单。
     *
     * <p>实现方需要负责：校验商品是否可售、校验并扣减库存（多件时任一不足则整单失败）、
     * 生成订单头与明细、写入下单时的联系方式快照。任一步失败抛
     * {@code BusinessException}，异常信息会原样展示给买家。</p>
     *
     * @param buyerId 下单买家
     * @param lines   明细（商品 + 数量 + 购物车里的参考单价）
     * @return 新建订单的 id
     */
    Long createOrder(Long buyerId, List<CartCheckoutLine> lines);
}

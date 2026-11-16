package com.group5.shop.cart.port;

/**
 * 下单明细（跨域传输对象，购物车 → 订单域）。
 *
 * <p>刻意<b>不用</b>购物车的实体 {@code Cart}，也不用订单域的实体：跨模块传的是
 * 「我买了什么、几件、当时看到多少钱」，换一方内部结构不该影响另一方。</p>
 *
 * @param productId      商品 id
 * @param quantity       购买数量
 * @param unitPriceCents 加购时看到的单价（分）。仅作参考，订单域以<b>下单时刻</b>的实际价格为准：
 *                       购物车里的价格可能是几天前加的，商品降价/涨价都该按当次结算价成交
 * @author 胡成溯（组长）
 * @see OrderCreatePort.Result 缺货按件返回，不整单失败
 */
public record CartCheckoutLine(Long productId, int quantity, long unitPriceCents) {
}

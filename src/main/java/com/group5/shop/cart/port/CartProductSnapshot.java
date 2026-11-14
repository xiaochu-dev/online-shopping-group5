package com.group5.shop.cart.port;

/**
 * 商品快照（跨域传输对象，商品域 → 购物车）。
 *
 * @param productId   商品 id
 * @param name        商品名称
 * @param priceCents  当前单价（分）
 * @param purchasable 是否可购买（在售且有库存）。为 {@code false} 时购物车拒绝加购
 * @author 胡成溯（组长）
 */
public record CartProductSnapshot(Long productId, String name, long priceCents, boolean purchasable) {
}

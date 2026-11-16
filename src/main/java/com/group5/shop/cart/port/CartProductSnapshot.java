package com.group5.shop.cart.port;

/**
 * 商品快照（跨域传输对象，商品域 → 购物车）。
 *
 * @param productId   商品 id
 * @param name        商品名称
 * @param priceCents  当前单价（分）
 * @param purchasable 是否可购买（在售且有库存）。为 {@code false} 时购物车拒绝加购
 * @param stock       当前可售库存。购物车用它做「数量不超库存」的提示性校验
 *                    （校验但不占用：抢到结算那一步仍由订单域的条件 UPDATE 决定）
 * @author 胡成溯（组长）
 */
public record CartProductSnapshot(Long productId, String name, long priceCents,
                                  boolean purchasable, int stock) {
}

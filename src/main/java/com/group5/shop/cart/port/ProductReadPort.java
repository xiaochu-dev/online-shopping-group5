package com.group5.shop.cart.port;

import java.util.Optional;

/**
 * 商品只读端口（消费方：购物车；提供方：商品域 / 黄程宇，任务 T4-24）。
 *
 * <p>购物车只在「加购」这一刻需要商品的当前名称、单价与可售状态。取不到就不允许加购，
 * 而不是拿一个空名字塞进购物车。</p>
 *
 * <p>为什么不用「直接查 product 表」：那会让购物车依赖商品域的表结构，商品域改字段
 * 就波及购物车。端口只暴露三个字段，商品域内部怎么改都不影响这里。</p>
 *
 * @author 胡成溯（组长）
 */
public interface ProductReadPort {

    /**
     * 按 id 读商品快照。
     *
     * @param productId 商品 id
     * @return 商品不存在时返回 {@link Optional#empty()}
     */
    Optional<CartProductSnapshot> find(Long productId);
}

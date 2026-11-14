package com.group5.shop.cart.port;

import java.util.List;

/**
 * 收藏端口（挑战需求 B.2.4，消费方：购物车；提供方：收藏域 / 黄程宇）。
 *
 * <p>「从购物车中选择多件商品转为收藏」——收藏表归收藏域，购物车只发起动作。
 * 语义要求（由实现方保证）：</p>
 * <ul>
 *   <li>幂等：同一买家同一商品重复转收藏，不产生重复行，也不报错；</li>
 *   <li>返回真正新增的条数，已收藏的计入「跳过」；</li>
 *   <li>不关心购物车是否随后删除条目——那一步由购物车自己决定。</li>
 * </ul>
 *
 * @author 胡成溯（组长）
 */
public interface FavoritePort {

    /**
     * 把若干商品加入当前买家的收藏。
     *
     * @param buyerId    买家 id
     * @param productIds 商品 id 列表
     * @return 本次新增的收藏条数（已收藏的不计）
     */
    int addToFavorites(Long buyerId, List<Long> productIds);
}

package com.group5.shop.cart.service;

/**
 * 「购物车转收藏」的执行结果（挑战需求 B.2.4）。
 *
 * <p><b>为什么需要两个数</b>：转收藏的口径是「把勾选行<b>移出</b>购物车，同时把商品<b>加入</b>收藏」。
 * 这两件事的数量不一定相等——勾 3 件里若有 2 件早就收藏过，收藏侧幂等跳过，
 * 实际是「移出 3 件、新增 1 条」。只报一个数必然对不上，页面上就会出现
 * 「已转收藏 1 件」但购物车少了 3 行的矛盾。</p>
 *
 * @param movedOut      从购物车移出的条数
 * @param favoriteAdded 收藏侧真正新增的条数（已收藏的不计）
 * @author 胡成溯（组长）
 */
public record FavoriteMoveResult(int movedOut, int favoriteAdded) {

    /** 是否全部为「此前已收藏」——用于给出更准确的提示语 */
    public boolean allAlreadyFavorited() {
        return movedOut > 0 && favoriteAdded == 0;
    }
}

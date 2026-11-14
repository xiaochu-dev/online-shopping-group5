package com.group5.shop.cart.service;

import com.group5.shop.cart.domain.Cart;
import com.group5.shop.cart.domain.CartMapper;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.CartProductSnapshot;
import com.group5.shop.cart.port.FavoritePort;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.cart.port.ProductReadPort;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 购物车服务（挑战需求 B.2.2 / B.2.3 / B.2.4，owner：胡成溯）。
 *
 * <p><b>三个跨域能力全部用端口取，缺失即降级</b>：</p>
 * <ul>
 *   <li>{@link ProductReadPort}（商品域提供）：加购时读商品名与单价。缺了就不让加购——
 *       宁可明确报「商品服务未接入」，也不往购物车里塞空名字；</li>
 *   <li>{@link OrderCreatePort}（订单域提供）：结算。缺了只影响结算按钮，
 *       加购/改数量/删除照常可用；</li>
 *   <li>{@link FavoritePort}（收藏域提供）：多件转收藏。缺了只影响「转收藏」按钮。</li>
 * </ul>
 *
 * <p>这样三个模块可以并行推进：任何一方还没提交，另外两方的功能仍然可以演示。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class CartService {

    /** 单条目的数量下限 */
    public static final int MIN_QUANTITY = 1;

    /** 单条目的数量上限：防止误输入把库存校验放大，也避免页面出现 5 位数 */
    public static final int MAX_QUANTITY = 99;

    private final CartMapper cartMapper;
    private final ObjectProvider<ProductReadPort> productReadPort;
    private final ObjectProvider<OrderCreatePort> orderCreatePort;
    private final ObjectProvider<FavoritePort> favoritePort;

    public CartService(CartMapper cartMapper,
                       ObjectProvider<ProductReadPort> productReadPort,
                       ObjectProvider<OrderCreatePort> orderCreatePort,
                       ObjectProvider<FavoritePort> favoritePort) {
        this.cartMapper = cartMapper;
        this.productReadPort = productReadPort;
        this.orderCreatePort = orderCreatePort;
        this.favoritePort = favoritePort;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 当前买家的购物车（最近改动在前） */
    public List<Cart> list(Long buyerId) {
        return cartMapper.selectByBuyer(buyerId);
    }

    /** 购物车合计金额（分） */
    public long totalCents(List<Cart> rows) {
        return rows.stream().mapToLong(Cart::getSubtotalCents).sum();
    }

    /** 结算能力是否已接入（订单域实现 {@link OrderCreatePort} 后为 true） */
    public boolean checkoutAvailable() {
        return orderCreatePort.getIfAvailable() != null;
    }

    /** 转收藏能力是否已接入（收藏域实现 {@link FavoritePort} 后为 true） */
    public boolean favoriteAvailable() {
        return favoritePort.getIfAvailable() != null;
    }

    // ==================================================================
    // 写：加购 / 改数量 / 删除
    // ==================================================================

    /**
     * 加入购物车；已存在同一商品则累加数量。
     *
     * <p>校验顺序：数量合法 → 商品可购买 → 累加后不超上限。顺序有讲究：
     * 先拦非法数量，避免拿一个明显错误的数字去查商品。</p>
     *
     * @return 写入/更新后的购物车行
     */
    @Transactional
    public Cart add(Long buyerId, Long productId, Integer quantity) {
        int delta = quantity == null ? 1 : quantity;
        if (delta < MIN_QUANTITY || delta > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "购买数量需在 " + MIN_QUANTITY + " ~ " + MAX_QUANTITY + " 件之间");
        }

        CartProductSnapshot snapshot = requirePurchasable(productId);
        long now = TimeUtils.nowMillis();

        Cart existing = cartMapper.selectByBuyerAndProduct(buyerId, productId);
        if (existing == null) {
            Cart row = new Cart();
            row.setBuyerId(buyerId);
            row.setProductId(productId);
            row.setQuantity(delta);
            row.setProductName(snapshot.name());
            row.setPriceCents(snapshot.priceCents());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            cartMapper.insert(row);
            return row;
        }

        int merged = existing.getQuantity() + delta;
        if (merged > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "同一商品最多 " + MAX_QUANTITY + " 件，购物车里已有 " + existing.getQuantity() + " 件");
        }
        // 顺带用最新一次加购的价格刷新快照：买家看到的是「最近一次加购时的价格」
        existing.setQuantity(merged);
        existing.setProductName(snapshot.name());
        existing.setPriceCents(snapshot.priceCents());
        cartMapper.updateQuantity(existing.getId(), buyerId, merged, now);
        existing.setUpdatedAt(now);
        return existing;
    }

    /**
     * 改数量。
     *
     * <p>{@code buyer_id} 进 SQL 的 where 条件，改不到别人的行；改不到时统一报
     * 「购物车条目不存在」，不区分「不存在」与「不是你的」。</p>
     */
    public void updateQuantity(Long buyerId, Long cartId, Integer quantity) {
        if (quantity == null || quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "购买数量需在 " + MIN_QUANTITY + " ~ " + MAX_QUANTITY + " 件之间");
        }
        int rows = cartMapper.updateQuantity(cartId, buyerId, quantity, TimeUtils.nowMillis());
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "购物车条目不存在");
        }
    }

    /** 删除单条（同样带 buyer_id 过滤） */
    public void remove(Long buyerId, Long cartId) {
        int rows = cartMapper.deleteByIdAndBuyer(cartId, buyerId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "购物车条目不存在");
        }
    }

    /** 清空购物车 */
    public void clear(Long buyerId) {
        cartMapper.deleteByBuyer(buyerId);
    }

    // ==================================================================
    // 写：结算 / 转收藏（跨域）
    // ==================================================================

    /**
     * 结算：把选中的条目交给订单域生成订单，成功后从购物车移除。
     *
     * @param cartIds 选中的购物车条目 id；为空表示整车结算
     * @return 新建订单 id
     */
    @Transactional
    public Long checkout(Long buyerId, List<Long> cartIds) {
        OrderCreatePort port = orderCreatePort.getIfAvailable();
        if (port == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "下单功能尚未接入：等待订单模块实现 OrderCreatePort 后自动生效");
        }

        List<Cart> selected = selectOwned(buyerId, cartIds);
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "购物车是空的，先去挑几件商品吧");
        }

        List<CartCheckoutLine> lines = selected.stream()
                .map(row -> new CartCheckoutLine(row.getProductId(), row.getQuantity(),
                        row.getPriceCents() == null ? 0L : row.getPriceCents()))
                .collect(Collectors.toList());

        Long orderId = port.createOrder(buyerId, lines);
        cartMapper.deleteByBuyerAndIds(buyerId, joinIds(selected));
        return orderId;
    }

    /**
     * 把选中的条目转为收藏，成功后从购物车移除（已收藏过的由收藏域幂等跳过）。
     *
     * @param cartIds 选中的购物车条目 id；为空表示整车转收藏
     * @return 本次新增的收藏条数
     */
    @Transactional
    public int moveToFavorites(Long buyerId, List<Long> cartIds) {
        FavoritePort port = favoritePort.getIfAvailable();
        if (port == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "收藏功能尚未接入：等待收藏模块实现 FavoritePort 后自动生效");
        }

        List<Cart> selected = selectOwned(buyerId, cartIds);
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先选择要转为收藏的商品");
        }

        List<Long> productIds = selected.stream().map(Cart::getProductId).collect(Collectors.toList());
        int added = port.addToFavorites(buyerId, productIds);
        cartMapper.deleteByBuyerAndIds(buyerId, joinIds(selected));
        return added;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 取商品快照；商品不存在或不可购买时给出明确原因 */
    private CartProductSnapshot requirePurchasable(Long productId) {
        ProductReadPort port = productReadPort.getIfAvailable();
        if (port == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "商品服务尚未接入：等待商品模块实现 ProductReadPort 后自动生效");
        }
        Optional<CartProductSnapshot> snapshot = port.find(productId);
        if (snapshot.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架");
        }
        if (!snapshot.get().purchasable()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该商品当前不可购买");
        }
        return snapshot.get();
    }

    /** 选出属于该买家的条目；{@code cartIds} 为空时返回整车 */
    private List<Cart> selectOwned(Long buyerId, List<Long> cartIds) {
        List<Cart> all = cartMapper.selectByBuyer(buyerId);
        if (cartIds == null || cartIds.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(row -> cartIds.contains(row.getId()))
                .collect(Collectors.toList());
    }

    /** 拼 id 列表（仅用于已校验过归属的 id，值来自库，非用户输入） */
    private String joinIds(List<Cart> rows) {
        return rows.stream().map(row -> String.valueOf(row.getId())).collect(Collectors.joining(","));
    }
}

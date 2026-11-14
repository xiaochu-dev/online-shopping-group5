package com.group5.shop.product.adapter;

import com.group5.shop.cart.port.CartProductSnapshot;
import com.group5.shop.cart.port.ProductReadPort;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 商品只读端口的实现（任务 T4-24，商品域 → 购物车）。
 *
 * <p><b>归属说明</b>：本文件按任务工作表属于<b>黄程宇</b>（收藏域 + 商品支撑）。
 * 2026-11-14 由胡成溯写了这版<b>最小实现</b>，目的是先把购物车链路打通、能演示；
 * 黄程宇接手时可自由重写实现（例如加入「商品是否被冻结」的额外判断），
 * 只要满足 {@link ProductReadPort} 的契约即可，购物车侧不需要任何改动。</p>
 *
 * <p>「可购买」的判定：在售（{@code on_sale}）且库存大于 0。冻结中、已下架、
 * 已删除的商品不允许加入购物车——但<b>已在购物车里的旧条目不会被自动清掉</b>，
 * 由结算时订单域按当下状态再次校验（购物车只是清单，不承担交易判定的最终责任）。</p>
 *
 * @author 胡成溯（代黄程宇实现 T4-24 最小版，待其复核）
 */
@Component
public class ProductReadProvider implements ProductReadPort {

    private final ProductMapper productMapper;

    public ProductReadProvider(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public Optional<CartProductSnapshot> find(Long productId) {
        if (productId == null) {
            return Optional.empty();
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return Optional.empty();
        }
        boolean onSale = ProductStatus.ON_SALE.equals(product.getStatus());
        boolean inStock = product.getStock() != null && product.getStock() > 0;
        long price = product.getPriceCents() == null ? 0L : product.getPriceCents();
        return Optional.of(new CartProductSnapshot(product.getId(), product.getName(), price,
                onSale && inStock));
    }
}

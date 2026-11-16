package com.group5.shop.portal.service;

import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.contract.IntentQueuePort;
import org.springframework.stereotype.Service;

/**
 * 商品域 → 订单域的交接适配（接口定义在商品域，实现在订单域）。
 *
 * <h2>迭代 4 的语义调整</h2>
 * <ul>
 *   <li>{@code hasTrading} → 改问「这个商品还有没有<b>未完成订单</b>」：
 *       商品域用它在「把库存调成 0」前做保护。B 里下单即扣库存，在途订单已经占了货，
 *       此时把库存清零等于让这些订单发不出货，应当拦住。</li>
 *   <li>{@code failByProduct} → <b>不再批量结束订单</b>，直接返回 0。A 阶段的规则是
 *       「成交才扣库存，售罄后排队的人永远轮不到」，所以要把排队单批量置失败；
 *       B 改成「下单即扣库存」，售罄时已下单的订单已经把货占住了，不该被取消。</li>
 * </ul>
 *
 * <p>保留适配类而不是删接口，是因为商品域 {@code StockService} 仍在调用它；
 * 那个接口属于商品域（黄程宇）的文件，跨域删除会让两边同时返工。</p>
 *
 * @author 周到（迭代 4）
 */
@Service
public class ProductQueueAdapter implements IntentQueuePort {

    private final IntentMapper mapper;

    public ProductQueueAdapter(IntentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * B 起「商品是否被订单冻结」这个概念退出：一张商品可以同时有多笔在途订单
     * （购物车多件下单、复购），把商品置为 {@code frozen} 会连带挡住后面的客户，
     * 与 B.2.2 / B.2.3 直接冲突。
     *
     * <p>库存占用由 {@code order_item} + 「下单即扣减」体现，不靠商品状态表达。
     * 因此这里恒返回 false；保留方法是为了不改商品域（黄程宇）的接口。</p>
     *
     * @return 恒 false
     */
    @Override
    public boolean hasTrading(Long productId) {
        return false;
    }

    /**
     * 库存归零时不再批量取消订单（B 口径，见类注释）。
     *
     * @return 固定 0，表示未影响任何订单
     */
    @Override
    public int failByProduct(Long productId, String reason) {
        return 0;
    }
}

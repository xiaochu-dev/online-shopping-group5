package com.group5.shop.cart.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.util.MoneyUtils;
import com.group5.shop.common.util.TimeUtils;

/**
 * 购物车条目（挑战需求 B.2.2 / B.2.3，owner：胡成溯）。
 *
 * <p><b>一个买家 + 一个商品 = 一行</b>：重复加购累加 {@code quantity}，不堆行。
 * 库层有唯一索引 {@code uq_cart_buyer_product} 兜底（见 {@code schema/05-cart.sql}）。</p>
 *
 * <p><b>为什么有 {@code productName} / {@code priceCents} 两个快照字段</b>：
 * 购物车页面要显示商品名与单价。若渲染时跨模块查 {@code product} 表，购物车域就与
 * 商品域耦合；这里沿用 {@code intent.buyer_name} 的快照做法，加购时记下当时的名称与单价。
 * 价格仅作参考，<b>下单时由订单域按当下价格与库存复核</b>。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("cart")
public class Cart {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 买家 id（逻辑外键 → buyer.id） */
    private Long buyerId;

    /** 商品 id（外键 → product.id） */
    private Long productId;

    /** 数量，1 ~ {@link com.group5.shop.cart.service.CartService#MAX_QUANTITY} */
    private Integer quantity;

    /** 加购时的商品名快照 */
    private String productName;

    /** 加购时的单价快照，单位「分」 */
    private Long priceCents;

    private Long createdAt;

    private Long updatedAt;

    // ==================================================================
    // 展示辅助（Thymeleaf 直接调用，避免模板里写算术）
    // ==================================================================

    /** 单价（元），如 {@code 12.90} */
    public String getPriceYuan() {
        return MoneyUtils.centsToYuan(priceCents);
    }

    /** 小计（分）= 单价 × 数量；空值时按 0 处理 */
    public long getSubtotalCents() {
        if (priceCents == null || quantity == null) {
            return 0L;
        }
        return priceCents * quantity;
    }

    /** 小计（元），如 {@code 25.80} */
    public String getSubtotalYuan() {
        return MoneyUtils.centsToYuan(getSubtotalCents());
    }

    /** 加购时间文案，供页面展示 */
    public String getUpdatedAtText() {
        return TimeUtils.formatDateTime(updatedAt);
    }

    // ==================================================================
    // getter / setter（与项目其它实体一致：手写，不引入 Lombok）
    // ==================================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Long priceCents) {
        this.priceCents = priceCents;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}

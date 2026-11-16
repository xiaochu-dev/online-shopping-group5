package com.group5.shop.portal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.util.MoneyUtils;

/**
 * 订单明细，对应表 {@code order_item}（T4-02）。
 *
 * <h2>为什么类名是 OrderLine 而不是 OrderItem</h2>
 * <p>{@link OrderItem} 这个类名已被「我的订单一行」的展示 record 占用（同包），
 * 再定义同名类会直接编译冲突（记录 §四 R7）。因此实体叫 {@code OrderLine}，
 * 展示 record 不动，页面与测试都不用改名。</p>
 *
 * <h2>为什么存快照</h2>
 * <p>商品之后改名、改价、下架，都不该让历史订单跟着变。
 * 所以 {@code productName} 与 {@code priceCents} 都是<b>下单那一刻</b>的值。</p>
 *
 * @author 周到（迭代 4 · T4-02）
 */
@TableName("order_item")
public class OrderLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属订单头 id */
    private Long intentId;

    private Long productId;

    /** 下单时的商品名快照 */
    private String productName;

    /** 下单时的单价快照，单位「分」 */
    private Integer priceCents;

    /** 数量，至少 1；同一订单内同一商品只有一行，数量已合并 */
    private Integer quantity;

    private Long createdAt;

    // ------------------------------------------------------------------
    // 页面便捷方法
    // ------------------------------------------------------------------

    /** 小计（分）= 单价 × 数量 */
    public long subtotalCents() {
        int price = priceCents == null ? 0 : priceCents;
        int count = quantity == null ? 0 : quantity;
        return (long) price * count;
    }

    /** 小计的元显示，如 {@code 59.80} */
    public String getSubtotalText() {
        return MoneyUtils.centsToYuan(subtotalCents());
    }

    /** 单价的元显示 */
    public String getPriceText() {
        return MoneyUtils.centsToYuan(priceCents == null ? 0L : priceCents.longValue());
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIntentId() {
        return intentId;
    }

    public void setIntentId(Long intentId) {
        this.intentId = intentId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Integer priceCents) {
        this.priceCents = priceCents;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}

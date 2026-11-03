package com.group5.shop.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.util.MoneyUtils;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.payment.constant.PaymentStatus;

/**
 * 支付单实体，对应表 {@code payment}（第 5 组 · 模拟支付模块建立）。
 *
 * <p>一条支付单对应一笔购买意向（{@code intent_id}），由「生成支付单」动作创建，
 * 随支付 / 退款在 {@link PaymentStatus} 的状态机里流转。</p>
 *
 * <h2>金额字段特别说明</h2>
 * <p>与商品表 {@code price_cents} 一致，数据库存的是 {@code amount_cents}（整数「分」），
 * 不是 double。页面用 {@code th:text="${payment.amountYuan}"} 直接拿到 {@code 899.00} 这样的字符串。</p>
 *
 * <h2>状态机（支付侧）</h2>
 * <pre>
 *   pending ──支付成功──→ paid ──发起退款──→ refunding ──退款成功──→ refunded
 *      │                    │                                        （终态）
 *      └──支付失败──→ failed└────退款失败────→ paid
 * </pre>
 * 合法流转由 {@link PaymentStatus#canTransit(String, String)} 维护，业务侧调用时拦截非法流转。
 *
 * @author 第 5 组（模拟支付模块）
 */
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联购买意向 id（intent 表），一对一 */
    private Long intentId;

    /** 付款人（买家）id */
    private Long buyerId;

    /** 支付金额，单位「分」 */
    private Long amountCents;

    /** pending / paid / refunding / refunded / failed，见 {@link PaymentStatus} */
    private String status;

    /** 支付成功时间（毫秒），未支付为 null */
    private Long paidAt;

    /** 退款成功时间（毫秒），未退款为 null */
    private Long refundedAt;

    /** 创建时间（毫秒） */
    private Long createdAt;

    /** 模拟支付流水号，形如 {@code PAY + 时间戳 + 随机数}，全局唯一 */
    private String tradeNo;

    // ------------------------------------------------------------------
    // 计算属性：不对应数据库列，只供 Thymeleaf / JSON 展示
    // ------------------------------------------------------------------

    /** 支付金额（元，两位小数字符串），如 899.00 */
    public String getAmountYuan() {
        return MoneyUtils.centsToYuan(amountCents);
    }

    /** 支付状态中文名，如「待支付」 */
    public String getStatusLabel() {
        return PaymentStatus.labelOf(status);
    }

    /** 创建时间，格式化为 {@code 2026-11-03 21:30} */
    public String getCreatedAtText() {
        return TimeUtils.formatDateTime(createdAt);
    }

    /** 支付时间，未支付为空串 */
    public String getPaidAtText() {
        return TimeUtils.formatDateTime(paidAt);
    }

    /** 退款时间，未退款为空串 */
    public String getRefundedAtText() {
        return TimeUtils.formatDateTime(refundedAt);
    }

    /** 供页面判断「模拟支付」按钮是否可点 */
    public boolean isPending() {
        return PaymentStatus.PENDING.equals(status);
    }

    /** 供页面判断「申请退款」按钮是否可点 */
    public boolean isPaid() {
        return PaymentStatus.PAID.equals(status);
    }

    /** 供页面展示「已退款」状态 */
    public boolean isRefunded() {
        return PaymentStatus.REFUNDED.equals(status);
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

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(Long amountCents) {
        this.amountCents = amountCents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Long paidAt) {
        this.paidAt = paidAt;
    }

    public Long getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(Long refundedAt) {
        this.refundedAt = refundedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }
}

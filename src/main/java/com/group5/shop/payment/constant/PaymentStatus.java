package com.group5.shop.payment.constant;

/**
 * 支付状态常量（第 5 组 · 模拟支付模块）。
 *
 * <h2>状态机（合法流转路径）</h2>
 * <pre>
 *   pending ──支付成功──→ paid ──发起退款──→ refunding ──退款成功──→ refunded
 *      │                    │                                          （终态）
 *      └──支付失败──→ failed└────退款失败────→ paid
 * </pre>
 *
 * <p>其中：
 * <ul>
 *   <li>{@code pending}：生成支付单后的初始状态，可支付；</li>
 *   <li>{@code paid}：模拟支付回调成功，已支付，可发起退款；</li>
 *   <li>{@code refunding}：退款申请已受理、等待退款回调的中间态（真实网关为异步）；</li>
 *   <li>{@code refunded}：退款成功，终态；</li>
 *   <li>{@code failed}：支付失败，终态。</li>
 * </ul>
 *
 * <p><b>非法流转必须拦截</b>：业务侧（{@code PaymentService}）在每次变更前调
 * {@link #canTransit(String, String)} 校验，不合法直接抛业务异常，
 * 不允许出现「已退款又支付」「重复支付」等越界。</p>
 *
 * @author 第 5 组（模拟支付模块）
 */
public final class PaymentStatus {

    /** 待支付：生成支付单后的初始状态 */
    public static final String PENDING = "pending";

    /** 已支付：模拟支付回调成功 */
    public static final String PAID = "paid";

    /** 退款中：退款申请已受理，等待退款回调 */
    public static final String REFUNDING = "refunding";

    /** 已退款：退款成功（终态） */
    public static final String REFUNDED = "refunded";

    /** 支付失败（终态） */
    public static final String FAILED = "failed";

    private PaymentStatus() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /** 状态值 → 页面展示的中文名 */
    public static String labelOf(String status) {
        if (PENDING.equals(status)) {
            return "待支付";
        }
        if (PAID.equals(status)) {
            return "已支付";
        }
        if (REFUNDING.equals(status)) {
            return "退款中";
        }
        if (REFUNDED.equals(status)) {
            return "已退款";
        }
        if (FAILED.equals(status)) {
            return "支付失败";
        }
        return "未知状态";
    }

    /**
     * 判断 {@code from → to} 是否为合法流转。
     *
     * <p>合法路径（与类注释的状态机一致）：
     * {@code pending → paid / failed}；{@code paid → refunding}；
     * {@code refunding → refunded / paid}。其余（含终态出发、跳步）一律非法。</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true = 合法；false = 非法
     */
    public static boolean canTransit(String from, String to) {
        if (PENDING.equals(from)) {
            return PAID.equals(to) || FAILED.equals(to);
        }
        if (PAID.equals(from)) {
            return REFUNDING.equals(to);
        }
        if (REFUNDING.equals(from)) {
            return REFUNDED.equals(to) || PAID.equals(to);
        }
        // refunded / failed 是终态，不能继续流转
        return false;
    }
}

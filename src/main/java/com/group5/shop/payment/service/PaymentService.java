package com.group5.shop.payment.service;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.payment.constant.PaymentStatus;
import com.group5.shop.payment.domain.Payment;
import com.group5.shop.payment.domain.PaymentMapper;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟支付核心业务（第 5 组 · 模拟支付模块）。
 *
 * <h2>迭代 4（挑战需求 B）：支付<b>不参与</b>订单状态机</h2>
 * <p>11-15 助教答复 Q7 明确：B 的五个状态由商家按序推进，<b>支付既不推进也不回退订单状态</b>。
 * 所以本模块只维护自己的支付单状态机，{@code intent.status} 一个字节都不写：</p>
 * <pre>
 *   payment: pending ──支付成功──→ paid ──发起退款──→ refunding ──退款成功──→ refunded
 * </pre>
 *
 * <p><b>为什么必须切断</b>：迭代 3 里「支付成功 → 订单 success」是一条硬编码回写。
 * B 把 {@code success} 从状态取值里删掉之后，这条回写会让支付一按下去就撞上
 * {@code status} 的 CHECK 约束，整笔事务回滚——表现是「支付按钮点了没反应」，
 * 排查起来还很绕。这就是任务里 T4-07 只有 1 点、却<b>必须今天做</b>的原因。</p>
 *
 * <h2>两条容易写错的地方</h2>
 * <ol>
 *   <li><b>金额用「分」</b>：支付单金额取自商品 {@code price_cents}，全程整数运算，
 *       不碰 double，杜绝 {@code 0.1 + 0.2 != 0.3} 这类精度问题；</li>
 *   <li><b>不扣库存</b>：B 的库存口径是「下单即扣、取消回补」，扣减发生在
 *       {@code OrderCreateProvider} 里。支付再扣一次会变成「一件货扣两次」。</li>
 * </ol>
 *
 * <h2>退款不回补库存</h2>
 * <p>退款只改支付单；要不要回补库存取决于订单是否取消，那是订单状态机的事
 * （{@code OrderStatusService} 在取消时负责回补）。支付模块直接动库存会绕过
 * 「取消才回补」这条口径。</p>
 *
 * @author 第 5 组（模拟支付模块）；周到 迭代 4 切断状态回写（T4-07）
 */
@Service
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;

    public PaymentService(PaymentMapper paymentMapper,
                          IntentMapper intentMapper,
                          ProductMapper productMapper) {
        this.paymentMapper = paymentMapper;
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
    }

    // ==================================================================
    // 1. 创建支付单（幂等）
    // ==================================================================

    /**
     * 生成支付单：一张意向最多一张，已存在则原样返回（幂等，页面可反复进入）。
     *
     * <p>金额<b>下单时快照</b>：取商品的当前 {@code price_cents} 抄进支付单，
     * 之后商品改价不影响历史支付单。</p>
     *
     * @param intentId 意向 id
     * @param buyer    当前登录买家（调用方已保证非空）
     * @return 新建或已存在的支付单
     * @throws BusinessException 订单不存在/不属于该买家、订单状态不可支付、商品价格异常
     */
    @Transactional
    public Payment createPayment(Long intentId, Buyer buyer) {
        Long buyerId = buyer.getId();

        // 幂等：已存在就直接返回，不重复生成；但必须先校验归属（越权拦截）
        Payment existing = paymentMapper.selectByIntentId(intentId);
        if (existing != null) {
            if (!buyerId.equals(existing.getBuyerId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "支付单不存在或不属于当前买家");
            }
            return existing;
        }

        Intent intent = requirePayableIntent(intentId, buyerId);
        Product product = productMapper.selectById(intent.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在，无法生成支付单");
        }
        Long amountCents = product.getPriceCents();
        if (amountCents == null || amountCents <= 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "商品价格异常，无法生成支付单");
        }

        Payment payment = new Payment();
        payment.setIntentId(intentId);
        payment.setBuyerId(buyerId);
        payment.setAmountCents(amountCents);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(TimeUtils.nowMillis());
        payment.setTradeNo(newTradeNo());
        paymentMapper.insert(payment);
        return payment;
    }

    // ==================================================================
    // 2. 模拟支付回调（pending → paid，只记流水）
    // ==================================================================

    /**
     * 模拟支付宝「支付成功」回调：只把支付单 {@code pending → paid}。
     *
     * <p><b>不碰订单状态、不扣库存</b>（T4-07）。订单的五个状态由商家推进，
     * 支付只是「钱到了」的记账动作；库存已在客户下单时扣过。</p>
     *
     * @param intentId 订单 id
     * @param buyer    当前登录买家
     * @return 更新后的支付单
     * @throws BusinessException 越权、支付单状态不可支付
     */
    @Transactional
    public Payment mockPayCallback(Long intentId, Buyer buyer) {
        Payment payment = requireOwnedPayment(intentId, buyer.getId());
        // 非法流转拦截：只有 pending 能 → paid
        requireTransit(payment.getStatus(), PaymentStatus.PAID, "支付");
        // 订单必须存在且属于本人；已取消的单不允许支付
        requirePayableIntent(intentId, buyer.getId());

        long now = TimeUtils.nowMillis();
        updateStatus(payment.getId(), PaymentStatus.PAID, now, null);
        return paymentMapper.selectById(payment.getId());
    }

    // ==================================================================
    // 3. 查询支付状态
    // ==================================================================

    /**
     * 查询支付单（含归属校验）。供支付页展示与「我的订单」等场景复用。
     *
     * @param intentId 意向 id
     * @param buyer    当前登录买家
     * @return 支付单
     * @throws BusinessException 支付单不存在或不属于该买家
     */
    public Payment getStatus(Long intentId, Buyer buyer) {
        return requireOwnedPayment(intentId, buyer.getId());
    }

    // ==================================================================
    // 4. 发起退款（paid → refunding → refunded，只改支付单）
    // ==================================================================

    /**
     * 发起退款：支付单 {@code paid → refunding → refunded}，<b>不动订单状态</b>。
     *
     * <p>真实网关的退款是<b>异步</b>的：申请后进入 {@code refunding}，等网关回调再置
     * {@code refunded}（失败则回 {@code paid}）。本模块是模拟支付，直接在同一事务内
     * 走完「受理 → 退款成功」两步，把 {@code refunding} 这一合法中间态也真实走一遍。</p>
     *
     * <p>退款是资金动作，订单是否还有效由状态机决定（T4-07）。B 里没有「退款」这个
     * 订单状态，把订单改成 failed 会造出一个六态之外的脏值。</p>
     *
     * @param intentId 订单 id
     * @param buyer    当前登录买家
     * @return 更新后的支付单
     * @throws BusinessException 越权、非已支付状态
     */
    @Transactional
    public Payment refund(Long intentId, Buyer buyer) {
        Payment payment = requireOwnedPayment(intentId, buyer.getId());

        // 第一步：paid → refunding（发起退款），非法状态（未支付/已退款）在此拦截
        requireTransit(payment.getStatus(), PaymentStatus.REFUNDING, "退款");
        long now = TimeUtils.nowMillis();
        updateStatus(payment.getId(), PaymentStatus.REFUNDING, null, null);

        // 第二步：refunding → refunded（模拟退款成功回调）
        requireTransit(PaymentStatus.REFUNDING, PaymentStatus.REFUNDED, "退款");
        updateStatus(payment.getId(), PaymentStatus.REFUNDED, null, now);

        return paymentMapper.selectById(payment.getId());
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 支付流水号：时间戳 + 6 位随机数，库层 UNIQUE 兜底防碰撞 */
    private String newTradeNo() {
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "PAY" + TimeUtils.nowMillis() + suffix;
    }

    /**
     * 取支付单并校验归属。找不到或不属于自己的，统一按「不存在」处理，
     * 避免通过报错差异让别人探测到「这笔单是谁的」。
     */
    private Payment requireOwnedPayment(Long intentId, Long buyerId) {
        Payment payment = paymentMapper.selectByIntentId(intentId);
        if (payment == null || !buyerId.equals(payment.getBuyerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "支付单不存在或不属于当前买家");
        }
        return payment;
    }

    /**
     * 校验订单存在、属于本人、且未被取消。
     *
     * <p>B 里支付与订单状态解耦，所以<b>只挡「已取消」</b>：取消后钱不该再进来。
     * 其余四种状态（created / confirmed / prepared / shipped）都允许支付——
     * 客户先付款、商家后发货是正常场景。</p>
     */
    private Intent requirePayableIntent(Long intentId, Long buyerId) {
        Intent intent = intentMapper.selectById(intentId);
        if (intent == null || !buyerId.equals(intent.getBuyerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前买家");
        }
        if (IntentStatus.CANCELLED.equals(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单已取消，无法支付");
        }
        return intent;
    }

    /** 状态机守卫：from → to 非法时抛异常，阻止越界流转 */
    private void requireTransit(String from, String to, String action) {
        if (!PaymentStatus.canTransit(from, to)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, action + "失败：非法状态流转「"
                    + PaymentStatus.labelOf(from) + " → " + PaymentStatus.labelOf(to) + "」");
        }
    }

    /**
     * 只更新支付单的状态与对应时间戳。
     *
     * <p>用 {@code updateById} 的空字段忽略语义：传 null 的时间戳不会被覆盖，
     * 例如「发起退款」只改 status、不动 paid_at。</p>
     */
    private void updateStatus(Long paymentId, String status, Long paidAt, Long refundedAt) {
        Payment patch = new Payment();
        patch.setId(paymentId);
        patch.setStatus(status);
        patch.setPaidAt(paidAt);
        patch.setRefundedAt(refundedAt);
        paymentMapper.updateById(patch);
    }
}

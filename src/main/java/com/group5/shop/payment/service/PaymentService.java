package com.group5.shop.payment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.payment.constant.PaymentStatus;
import com.group5.shop.payment.domain.Payment;
import com.group5.shop.payment.domain.PaymentMapper;
import com.group5.shop.portal.contract.ProductPort;
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
 * <p>把流程从「线下交易」升级为「线上模拟支付」：
 * 买家提交意向（{@code intent.status = pending}）后生成支付单，买家「模拟支付」
 * 成功后订单自动完成；此后可发起退款，退款后订单回退为失败。</p>
 *
 * <h2>订单状态机（本模块负责的部分）</h2>
 * <pre>
 *   intent: pending ──支付成功──→ success ──退款──→ failed
 * </pre>
 * 与之联动的是支付单自身状态机（见 {@link PaymentStatus}）：
 * <pre>
 *   payment: pending ──支付成功──→ paid ──发起退款──→ refunding ──退款成功──→ refunded
 * </pre>
 *
 * <h2>三条容易写错的地方</h2>
 * <ol>
 *   <li><b>金额用「分」</b>：支付单金额取自商品 {@code price_cents}，全程整数运算，
 *       不碰 double，杜绝 {@code 0.1 + 0.2 != 0.3} 这类精度问题；</li>
 *   <li><b>先翻订单、再扣库存</b>：扣库存触发「售罄批量置失败」时只影响<b>其它</b>
 *       还在排队的意向，绝不能误伤本单；扣不动则抛异常让整个事务回滚；</li>
 *   <li><b>越权拦截</b>：每个动作都校验「支付单/订单属于当前买家」，查到不属于自己
 *       的单统一按「不存在」处理，不暴露别人的订单是否存在。</li>
 * </ol>
 *
 * <h2>退款不恢复库存（需向评审说明）</h2>
 * <p>本迭代只把订单状态从 success 回退为 failed，<b>不</b>回补商品库存：商品可能
 * 已再次卖出，回补会引入「退款又超卖」的新问题。若后续要支持，需新增
 * {@code ProductPort.restock} 并在商品中心实现，本模块不直接写 product 表。</p>
 *
 * @author 第 5 组（模拟支付模块）
 */
@Service
public class PaymentService {

    /** 退款后写入订单的失败原因（「我的订单」里买家能看到） */
    private static final String REFUND_REASON = "已退款";

    private final PaymentMapper paymentMapper;
    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;
    private final ProductPort productPort;

    public PaymentService(PaymentMapper paymentMapper,
                          IntentMapper intentMapper,
                          ProductMapper productMapper,
                          ProductPort productPort) {
        this.paymentMapper = paymentMapper;
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
        this.productPort = productPort;
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
    // 2. 模拟支付回调（pending → paid，订单自动完成）
    // ==================================================================

    /**
     * 模拟支付宝「支付成功」回调：支付单 {@code pending → paid}，订单 {@code pending → success}。
     *
     * <p>不接真实网关：本方法就是那个「回调」。支付流水号 {@code trade_no} 在生成支付单时
     * 已写入，这里只落支付时间。</p>
     *
     * <p><b>顺序说明</b>：先把订单从 pending 翻成 success，再调 {@code productPort.deduct} 扣
     * 1 件库存。因为扣库存会把「售罄后剩余排队意向」批量置失败，若先扣再翻单，
     * 本单（当时还是 pending）也会被那批 UPDATE 误伤。扣不动（库存不足）会抛异常，
     * {@code @Transactional} 让订单与支付单一起回滚，状态不脏。</p>
     *
     * @param intentId 意向 id
     * @param buyer    当前登录买家
     * @return 更新后的支付单
     * @throws BusinessException 越权、支付单/订单状态不可支付、库存不足
     */
    @Transactional
    public Payment mockPayCallback(Long intentId, Buyer buyer) {
        Payment payment = requireOwnedPayment(intentId, buyer.getId());
        // 非法流转拦截：只有 pending 能 → paid
        requireTransit(payment.getStatus(), PaymentStatus.PAID, "支付");

        Intent intent = requirePayableIntent(intentId, buyer.getId());
        long now = TimeUtils.nowMillis();

        // 1) 订单 pending → success（乐观条件：必须还是 pending，防止并发下重复完成）
        int updated = intentMapper.update(null, Wrappers.<Intent>lambdaUpdate()
                .eq(Intent::getId, intentId)
                .eq(Intent::getStatus, IntentStatus.PENDING)
                .set(Intent::getStatus, IntentStatus.SUCCESS)
                .set(Intent::getTradeResult, IntentStatus.SUCCESS)
                .set(Intent::getFinishedAt, now)
                .set(Intent::getFailReason, null));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单状态已变化，请刷新后重试");
        }

        // 2) 扣 1 件库存；扣不动抛异常，整体回滚
        productPort.deduct(intent.getProductId());

        // 3) 支付单 pending → paid
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
    // 4. 发起退款（paid → refunding → refunded，订单 success → failed）
    // ==================================================================

    /**
     * 发起退款：支付单 {@code paid → refunding → refunded}，订单 {@code success → failed}。
     *
     * <p>真实网关的退款是<b>异步</b>的：申请后进入 {@code refunding}，等网关回调再置
     * {@code refunded}（失败则回 {@code paid}）。本模块是模拟支付，直接在同一事务内
     * 走完「受理 → 退款成功」两步，把 {@code refunding} 这一合法中间态也真实走一遍。</p>
     *
     * @param intentId 意向 id
     * @param buyer    当前登录买家
     * @return 更新后的支付单
     * @throws BusinessException 越权、非已支付状态、订单状态异常
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

        // 订单回退：success → failed，写明「已退款」
        int updated = intentMapper.update(null, Wrappers.<Intent>lambdaUpdate()
                .eq(Intent::getId, intentId)
                .eq(Intent::getStatus, IntentStatus.SUCCESS)
                .set(Intent::getStatus, IntentStatus.FAILED)
                .set(Intent::getTradeResult, IntentStatus.FAILED)
                .set(Intent::getFailReason, REFUND_REASON)
                .set(Intent::getFinishedAt, now));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单状态异常，无法退款");
        }

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

    /** 校验订单归属 + 状态为「可支付」（pending） */
    private Intent requirePayableIntent(Long intentId, Long buyerId) {
        Intent intent = intentMapper.selectById(intentId);
        if (intent == null || !buyerId.equals(intent.getBuyerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前买家");
        }
        if (!IntentStatus.PENDING.equals(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "当前订单状态不可支付：" + intent.getStatusLabel());
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

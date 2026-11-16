package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.OrderLine;
import com.group5.shop.portal.domain.OrderLineMapper;
import com.group5.shop.portal.port.StockBatchPort;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单状态机（T4-03）：五态推进 + 两条取消关闸线 + 越权与非法跳转拦截。
 *
 * <h2>唯一的状态写入口</h2>
 * <p>本类是 {@code intent.status} 的<b>唯一写入口</b>。卖家页、买家页、支付回调都不许
 * 直接改状态，必须走这里——否则「谁在什么条件下能改」这条规则会被复制到 N 个地方，
 * 改一次规则就要改 N 处，必然漏。</p>
 *
 * <h2>两条关闸线（B.1.2 / B.1.3）</h2>
 * <pre>
 *   created ──商家确认──→ confirmed ──备货完成──→ prepared ──开始发货──→ shipped ──交易完成──→ completed
 *      │                     │                      │                        │
 *      └── 客户可取消 ────────┴──────────────────────┘                        │
 *                                            客户到此为止，不可再取消 ───────┘
 *      商家从 created 到 shipped 都可以取消（完成前），completed / cancelled 之后都不行
 * </pre>
 *
 * <h2>取消为什么要回补库存</h2>
 * <p>B 的库存口径是「下单即扣、取消回补」（11-15 答复 Q2）。回补只对<b>有明细行</b>的订单做：
 * 迭代 1~3 的老意向单从来没有扣过库存，若照 Q2 无条件 +1，库存会凭空变多。
 * 这条判断写在这里而不是交给商品域，是因为「这张单到底扣没扣过」只有订单域知道。</p>
 *
 * @author 周到（迭代 4 · T4-03）
 */
@Service
public class OrderStatusService {

    /** 商家取消未填原因时的默认文案 */
    private static final String DEFAULT_SELLER_CANCEL_REASON = "商家取消了订单";

    /** 客户取消未填原因时的默认文案 */
    private static final String DEFAULT_BUYER_CANCEL_REASON = "客户取消了订单";

    private final IntentMapper intentMapper;
    private final OrderLineMapper orderLineMapper;
    private final ProductMapper productMapper;
    private final ObjectProvider<StockBatchPort> stockBatchPort;

    public OrderStatusService(IntentMapper intentMapper,
                              OrderLineMapper orderLineMapper,
                              ProductMapper productMapper,
                              ObjectProvider<StockBatchPort> stockBatchPort) {
        this.intentMapper = intentMapper;
        this.orderLineMapper = orderLineMapper;
        this.productMapper = productMapper;
        this.stockBatchPort = stockBatchPort;
    }

    // ==================================================================
    // 商家推进：每次一步，自动算下一态
    // ==================================================================

    /**
     * 商家推进一步：确认 → 备货完成 → 开始发货 → 交易完成。
     *
     * <p>只允许「沿链走一步」：跳级（created → shipped）、倒退、终局后再推进都会被拒。
     * 目标状态由 {@link IntentStatus#next(String)} 算出，调用方不需要（也不允许）自己指定，
     * 这样「能不能跳级」就不存在被绕过的可能。</p>
     *
     * @param intentId 订单 id
     * @param sellerId 当前登录卖家 id
     * @return 推进后的订单
     * @throws BusinessException 订单不存在、不属于该卖家、已在终局、或并发下状态已变
     */
    @Transactional
    public Intent advance(Long intentId, Long sellerId) {
        Intent intent = requireSellerOrder(intentId, sellerId);
        String from = intent.getStatus();
        String to = IntentStatus.next(from);
        if (to == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "当前状态「" + IntentStatus.labelOf(from) + "」不能再往后推进");
        }

        Intent patch = new Intent();
        patch.setStatus(to);
        if (IntentStatus.COMPLETED.equals(to)) {
            patch.setFinishedAt(TimeUtils.nowMillis());
        }
        int updated = transit(intentId, from, patch);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单状态已变化，请刷新后重试");
        }
        return intentMapper.selectById(intentId);
    }

    // ==================================================================
    // 两条取消关闸线
    // ==================================================================

    /**
     * 客户取消：{@code created / confirmed / prepared} 可取消，{@code shipped} 之后不可。
     *
     * @param intentId 订单 id
     * @param buyerId  当前登录买家 id
     * @param reason   取消原因，可空
     * @return 取消后的订单
     * @throws BusinessException 订单不存在/不属于该买家、已经发货或已终局
     */
    @Transactional
    public Intent cancelByBuyer(Long intentId, Long buyerId, String reason) {
        Intent intent = requireIntent(intentId);
        if (buyerId == null || !buyerId.equals(intent.getBuyerId())) {
            // 不区分「不存在」与「不是你的」，避免用报错差异探测别人的单号
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前客户");
        }
        if (!IntentStatus.canBuyerCancel(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "订单已「" + IntentStatus.labelOf(intent.getStatus())
                            + "」，不能再取消（商家开始发货后客户不可取消）");
        }
        return cancel(intent, IntentStatus.CANCELLED_BY_BUYER, reason, DEFAULT_BUYER_CANCEL_REASON);
    }

    /**
     * 商家取消：{@code completed} 之前都可取消（含已发货的 {@code shipped}）。
     *
     * @param intentId 订单 id
     * @param sellerId 当前登录卖家 id
     * @param reason   取消原因，可空
     * @return 取消后的订单
     * @throws BusinessException 订单不存在/不属于该卖家、已完成或已取消
     */
    @Transactional
    public Intent cancelBySeller(Long intentId, Long sellerId, String reason) {
        Intent intent = requireSellerOrder(intentId, sellerId);
        if (!IntentStatus.canSellerCancel(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "订单已「" + IntentStatus.labelOf(intent.getStatus()) + "」，不能再取消");
        }
        return cancel(intent, IntentStatus.CANCELLED_BY_SELLER, reason, DEFAULT_SELLER_CANCEL_REASON);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 取消的公共流程：改状态 → 记取消方与时间 → 回补库存。
     *
     * <p>三件事必须在同一个事务里：状态改了但库存没回补，等于把货弄丢；
     * 库存回补了但状态没改，客户会看到「已取消的订单还能发货」。</p>
     */
    private Intent cancel(Intent intent, String cancelledBy, String reason, String defaultReason) {
        long now = TimeUtils.nowMillis();
        Intent patch = new Intent();
        patch.setStatus(IntentStatus.CANCELLED);
        patch.setCancelledBy(cancelledBy);
        patch.setCancelledAt(now);
        patch.setFinishedAt(now);
        patch.setFailReason(StringUtils.hasText(reason) ? reason.trim() : defaultReason);

        int updated = transit(intent.getId(), intent.getStatus(), patch);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单状态已变化，请刷新后重试");
        }

        restoreStockIfDeducted(intent.getId());
        return intentMapper.selectById(intent.getId());
    }

    /**
     * 取消时回补库存。
     *
     * <p><b>没有明细行就不回补</b>：迭代 1~3 的老意向单没扣过库存，
     * 无条件 +1 会让库存凭空变多（记录 §七 第 3 条的反面例子）。</p>
     */
    private void restoreStockIfDeducted(Long intentId) {
        List<OrderLine> lines = orderLineMapper.selectByIntentId(intentId);
        if (lines.isEmpty()) {
            return;
        }

        List<StockBatchPort.Demand> demands = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            demands.add(new StockBatchPort.Demand(line.getProductId(), line.getQuantity()));
        }

        StockBatchPort port = stockBatchPort.getIfAvailable();
        if (port == null) {
            // 有明细却没有库存端口：说明商品域没接上，此时静默跳过会让库存永久少掉
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "库存回补服务尚未接入，无法取消订单（StockBatchPort 待商品域实现）");
        }
        port.restore(intentId, demands);
    }

    /**
     * 带「当前状态」条件的更新，防止并发下两次操作都成功。
     *
     * <p>用条件 UPDATE 而不是先查后改：两个窗口同时点「确认」，先查后改会让两次都通过校验，
     * 结果是同一张单被推进两格。</p>
     */
    private int transit(Long intentId, String fromStatus, Intent patch) {
        return intentMapper.update(null, Wrappers.<Intent>lambdaUpdate()
                .eq(Intent::getId, intentId)
                .eq(Intent::getStatus, fromStatus)
                .set(Intent::getStatus, patch.getStatus())
                .set(patch.getFinishedAt() != null, Intent::getFinishedAt, patch.getFinishedAt())
                .set(patch.getCancelledAt() != null, Intent::getCancelledAt, patch.getCancelledAt())
                .set(patch.getCancelledBy() != null, Intent::getCancelledBy, patch.getCancelledBy())
                .set(patch.getFailReason() != null, Intent::getFailReason, patch.getFailReason()));
    }

    private Intent requireIntent(Long intentId) {
        Intent intent = intentId == null ? null : intentMapper.selectById(intentId);
        if (intent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return intent;
    }

    /**
     * 取订单并校验商家归属。
     *
     * <p>{@code intent} 表没有 {@code seller_id}：订单属于商品，商品属于卖家。
     * 所以归属校验必须经过商品表，不能省。</p>
     *
     * <p><b>多件订单看明细</b>：B.2.3 允许一张订单含多件商品，而这些商品可能属于不同卖家。
     * 只按订单头的 {@code product_id}（首件）判定，会让第二个卖家永远推不动这张单，
     * 变成谁都处理不了的死单。所以改为「订单内<b>任一件</b>商品属于该卖家即可操作整单」，
     * 这是 B 没有要求按卖家拆单时最省事且不会卡死的口径。</p>
     */
    private Intent requireSellerOrder(Long intentId, Long sellerId) {
        Intent intent = requireIntent(intentId);
        if (sellerId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前商家");
        }

        List<OrderLine> lines = orderLineMapper.selectByIntentId(intentId);
        List<Long> productIds = lines.isEmpty()
                ? List.of(intent.getProductId())
                : lines.stream().map(OrderLine::getProductId).distinct().toList();
        for (Long productId : productIds) {
            Product product = productMapper.selectById(productId);
            if (product != null && sellerId.equals(product.getSellerId())) {
                return intent;
            }
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不属于当前商家");
    }
}

package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.portal.contract.ProductPort;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.QueueRow;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卖家交易确认与意向管理（T2-18，A.1.3 + A.2.5 的落地动作）。
 *
 * <h2>状态机（澄清结论：没有独立的「取消」状态）</h2>
 * <pre>
 *   pending ──确认交易──→ trading ──交易成功──→ success   （扣 1 件库存）
 *      ↑                     │
 *      └──塞回队尾───────────┴──交易失败──→ failed        （库存不动，买家可选塞回）
 * </pre>
 *
 * <h2>三条容易写错的地方</h2>
 * <ol>
 *   <li><b>只有队首能进入交易</b>：先到先得不是口号，跳着确认会让队列失去意义，
 *       所以 confirm 会校验「待处理队列第一条就是它」；</li>
 *   <li><b>扣库存在「交易成功」那一刻</b>，不在提交意向时，也不在确认交易时。
 *       确认交易只是把商品置 {@code frozen}，商品级互斥，天然不会超卖；</li>
 *   <li><b>失败不扣库存、商品立刻回到在售</b>，后面排队的人接着按顺序走；
 *       失败的那个买家由卖家二选一：作废（置 failed）或塞回队尾。</li>
 * </ol>
 *
 * @author 周到
 */
@Service
public class SellerIntentService {

    /** 交易失败的默认原因 */
    private static final String DEFAULT_FAIL_REASON = "交易未达成";

    /** 库存扣到 0 时，给剩下排队者的统一失败原因（A.1.4） */
    private static final String SOLD_OUT_REASON = "商品库存已售罄，本次排队自动结束";

    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;
    private final ProductPort productPort;

    public SellerIntentService(IntentMapper intentMapper,
                               ProductMapper productMapper,
                               ProductPort productPort) {
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
        this.productPort = productPort;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 卖家名下全部商品（含已下架），按发布时间倒序。
     *
     * <p>本迭代没有商品删除功能，卖完的商品会一直留在列表里，
     * 因此页面上必须能看到状态并区分「卖光下架 / 手动下架」。</p>
     *
     * @param sellerId 卖家 id
     * @return 商品列表
     */
    public List<Product> productsOf(Long sellerId) {
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getSellerId, sellerId)
                .orderByDesc(Product::getPublishedAt)
                .orderByDesc(Product::getId));
    }

    /**
     * 各商品的未结束意向数，键为商品 id。
     *
     * @return 商品 id → 条数
     */
    public Map<Long, Integer> activeIntentCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : intentMapper.countActiveGroupByProduct()) {
            Object productId = row.get("product_id");
            Object cnt = row.get("cnt");
            if (productId instanceof Number pid && cnt instanceof Number c) {
                counts.put(pid.longValue(), c.intValue());
            }
        }
        return counts;
    }

    /**
     * 某商品未结束的意向队列（排队中 + 交易中），位次从 1 开始。
     *
     * @param productId 商品 id
     * @return 队列；无数据返回空列表
     */
    public List<QueueRow> queueOf(Long productId) {
        List<Intent> active = intentMapper.selectActiveQueue(productId);
        List<QueueRow> rows = new ArrayList<>(active.size());
        int position = 1;
        for (Intent intent : active) {
            rows.add(new QueueRow(position++, intent));
        }
        return rows;
    }

    /**
     * 取商品并校验归属，防止卖家操作别人的商品。
     *
     * @param productId 商品 id
     * @param sellerId  当前登录卖家 id
     * @return 商品
     * @throws BusinessException 商品不存在或不属于该卖家
     */
    public Product requireOwnedProduct(Long productId, Long sellerId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        if (sellerId != null && !sellerId.equals(product.getSellerId())) {
            // 单卖家场景下几乎不会触发；多商家入驻后这是越权入口，必须挡住
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    // ==================================================================
    // 动作 1：确认交易（pending → trading，商品冻结）
    // ==================================================================

    /**
     * 把队首意向推进到「交易中」，同时冻结商品。
     *
     * @param intentId 意向 id
     * @return 被推进的意向
     * @throws BusinessException 不是排队中、不是队首、或商品当前不可交易
     */
    @Transactional
    public Intent confirmTrade(Long intentId) {
        Intent intent = requireIntent(intentId);
        if (!IntentStatus.PENDING.equals(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有「排队中」的意向可以进入交易，当前状态：" + intent.getStatusLabel());
        }

        Product product = productMapper.selectById(intent.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        if (ProductStatus.FROZEN.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该商品已有一笔进行中的交易，请先处理它");
        }
        if (!ProductStatus.ON_SALE.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该商品已下架，无法进入交易");
        }

        // 先到先得：待处理队列的第一条必须就是它
        List<Intent> queue = intentMapper.selectQueue(intent.getProductId());
        if (queue.isEmpty() || !queue.get(0).getId().equals(intentId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "必须先处理队首的意向（先到先得）");
        }

        Intent patch = new Intent();
        patch.setId(intentId);
        patch.setStatus(IntentStatus.TRADING);
        intentMapper.updateById(patch);

        productPort.freeze(intent.getProductId());
        return intentMapper.selectById(intentId);
    }

    // ==================================================================
    // 动作 2：交易成功（trading → success，扣 1 件）
    // ==================================================================

    /**
     * 标记交易成功：<b>此刻</b>扣减 1 件库存，订单置 success，商品解冻回在售。
     *
     * <p>库存扣到 0 的自动下架由商品中心（T2-10）负责，这里只负责把商品从
     * {@code frozen} 放回 {@code on_sale}。</p>
     *
     * @param intentId 意向 id
     * @return 更新后的意向
     * @throws BusinessException 不是交易中，或库存不足
     */
    @Transactional
    public Intent markSuccess(Long intentId) {
        Intent intent = requireTrading(intentId);

        // 先扣库存：扣不动就不该把订单置成功，抛异常让整个事务回滚
        productPort.deduct(intent.getProductId());

        Intent patch = new Intent();
        patch.setId(intentId);
        patch.setStatus(IntentStatus.SUCCESS);
        patch.setTradeResult(IntentStatus.SUCCESS);
        patch.setFinishedAt(TimeUtils.nowMillis());
        intentMapper.updateById(patch);

        productPort.release(intent.getProductId());

        // A.1.4：库存扣到 0 时，后面排队的人已经不可能成交，批量置失败并写明原因。
        // stockOf() 在商品表还没有 stock 字段时返回 null（按「不限」处理），此时不动作。
        Integer left = productPort.stockOf(intent.getProductId());
        if (left != null && left <= 0) {
            intentMapper.failPendingOfProduct(intent.getProductId(), SOLD_OUT_REASON,
                    TimeUtils.nowMillis());
        }
        return intentMapper.selectById(intentId);
    }

    // ==================================================================
    // 动作 3：交易失败（trading → failed，或塞回队尾）
    // ==================================================================

    /**
     * 标记交易失败：库存不动，商品解冻回在售，并处置该买家。
     *
     * @param intentId 意向 id
     * @param requeue  true = 塞回队尾重新排队；false = 作废（置 failed）
     * @param reason   失败原因，为空时用「交易未达成」
     * @return 更新后的意向
     * @throws BusinessException 不是交易中，或要塞回时该买家已有另一条未结束意向
     */
    @Transactional
    public Intent markFailed(Long intentId, boolean requeue, String reason) {
        Intent intent = requireTrading(intentId);
        String text = StringUtils.hasText(reason) ? reason.trim() : DEFAULT_FAIL_REASON;

        Intent patch = new Intent();
        patch.setId(intentId);
        patch.setFailReason(text);

        if (requeue) {
            // 塞回队尾 = 回到 pending 并刷新排序时间；一条意向不该同时占两个位置
            if (intentMapper.countActiveByBuyer(intent.getBuyerId(), intent.getProductId()) >= 2) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "该买家已另有一条排队中的意向，塞回会重复占位；请改选「作废」");
            }
            patch.setStatus(IntentStatus.PENDING);
            patch.setCreatedAt(TimeUtils.nowMillis());
            patch.setTradeResult(null);
        } else {
            patch.setStatus(IntentStatus.FAILED);
            patch.setTradeResult(IntentStatus.FAILED);
            patch.setFinishedAt(TimeUtils.nowMillis());
        }

        // updateById 默认忽略 null 字段，作废路径要显式保留 finished_at 即可，
        // 塞回路径的 tradeResult / finishedAt 保持原来的 null，无需额外清空
        intentMapper.updateById(patch);

        productPort.release(intent.getProductId());
        return intentMapper.selectById(intentId);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 意向所属商品 id，用于动作完成后跳回该商品的队列页。
     *
     * @param intentId 意向 id
     * @return 商品 id；意向不存在返回 {@code null}
     */
    public Long productIdOf(Long intentId) {
        Intent intent = intentMapper.selectById(intentId);
        return intent == null ? null : intent.getProductId();
    }

    private Intent requireIntent(Long intentId) {
        Intent intent = intentMapper.selectById(intentId);
        if (intent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "意向不存在");
        }
        return intent;
    }

    private Intent requireTrading(Long intentId) {
        Intent intent = requireIntent(intentId);
        if (!IntentStatus.TRADING.equals(intent.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "只有「交易中」的意向可以标记结果，当前状态：" + intent.getStatusLabel());
        }
        return intent;
    }
}

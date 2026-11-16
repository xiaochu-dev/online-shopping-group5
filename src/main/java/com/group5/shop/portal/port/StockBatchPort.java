package com.group5.shop.portal.port;

import java.util.List;

/**
 * 订单域对商品中心的库存入口：<b>批量、逐件、可回补</b>（T4-23，由商品域实现）。
 *
 * <h2>为什么不是「全部成功或全部失败」</h2>
 * <p>11-15 助教答复 Q3 明确：多件下单时<b>逐件校验</b>，库存不足的那件提示缺货、下不了单，
 * 其余有货的<b>照常下单</b>；没有整单回滚，也不拆单。所以本接口<b>不抛整单异常</b>，
 * 而是把「扣成功的」与「缺货的」分成两组返回。</p>
 *
 * <p>任务工作表 §三 里写的「失败整单回滚」与答复相反，以本接口为准（记录 §四 R2）。</p>
 *
 * <h2>为什么回补也在这里</h2>
 * <p>B 的库存口径是「下单即扣、取消回补」（Q2）。扣与回补是同一个业务概念的两面，
 * 拆成两个端口会让商品域实现两次「找商品 + 判断上下架」。回补还有一个容易漏的点：
 * 商品因库存归零被自动下架后，回补时必须同时恢复上架，否则「商品重新可售」落不了地
 * （记录 §四 R4）。</p>
 *
 * @author 周到（迭代 4 · T4-23 的消费方）
 */
public interface StockBatchPort {

    /**
     * 逐件校验并扣减。
     *
     * <p>对每一件：可用量 ≥ 需求量 → 扣减并计入 {@code deducted}；
     * 否则<b>一件都不扣</b>，整个请求计入 {@code shortages}，并附上当时可用量。
     * 已下架商品按「可用量 0」处理，{@code reason} 标「已下架」。</p>
     *
     * @param demands 需求列表，同一商品重复出现时由调用方先合并
     * @return 扣减结果，两组互斥且并集覆盖全部请求
     */
    DeductResult deduct(List<Demand> demands);

    /**
     * 回补库存（订单取消时调用）。
     *
     * <p>每件 {@code stock += quantity}；若该商品处于「因库存归零而自动下架」的状态，
     * 回补后要一并恢复 {@code on_sale}。手工下架的商品<b>不</b>自动上架——
     * 那是商家的明确意图，不该被一次取消动作推翻。</p>
     *
     * <p>本方法必须<b>幂等</b>：同一订单重复调用不得重复累加，实现方以调用方传入的
     * {@code orderId} 做去重依据。</p>
     *
     * @param orderId 订单 id，用于幂等去重
     * @param demands 需要回补的明细
     */
    void restore(Long orderId, List<Demand> demands);

    /**
     * 一条库存需求。
     *
     * @param productId 商品 id
     * @param quantity  数量，至少 1
     */
    record Demand(Long productId, int quantity) {
    }

    /**
     * 扣减结果。
     *
     * @param deducted  扣减成功的件
     * @param shortages 被拦下的件
     */
    record DeductResult(List<Demand> deducted, List<Shortage> shortages) {

        public DeductResult {
            deducted = deducted == null ? List.of() : List.copyOf(deducted);
            shortages = shortages == null ? List.of() : List.copyOf(shortages);
        }
    }

    /**
     * 被拦下的一件。
     *
     * @param productId 商品 id
     * @param requested 需求数量
     * @param available 当时可售数量（下架为 0）
     * @param reason    「库存不足」/「已下架」
     */
    record Shortage(Long productId, int requested, int available, String reason) {
    }
}

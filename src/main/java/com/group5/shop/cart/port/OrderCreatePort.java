package com.group5.shop.cart.port;

import java.util.List;

/**
 * 下单端口（挑战需求 B.2.3，消费方：购物车；提供方：订单域 / 周到）。
 *
 * <p><b>为什么由购物车定义接口</b>：按团队既有的「消费方定义端口、提供方实现」约定
 * （参见 {@code admin/port/IntentHistoryPort}），购物车只声明「我需要一个能按明细下单的
 * 能力」，具体怎么生成订单、怎么扣库存、怎么落五位状态机，全是订单域的实现细节。
 * 购物车不 import 订单域的任何类，因此两边可以并行开发、互不看对方代码。</p>
 *
 * <p><b>提供方尚未实现时的行为</b>：{@link com.group5.shop.cart.service.CartService}
 * 用 {@code ObjectProvider} 取实现，取不到时结算按钮给出「下单功能尚未接入」提示，
 * 购物车其余功能（加购、改数量、删除）不受影响。</p>
 *
 * <h2>2026-11-16 契约变更：从「返回 id」改为「返回结果对象」</h2>
 * <p>原签名是 {@code Long createOrder(...)}，注释写「多件时任一不足则整单失败」。
 * 这跟 11-15 助教答复 Q3 的<b>逐件拦截</b>直接冲突：多件里有一件缺货时，其余件应当
 * 照常下单，缺货件提示买家——整单失败会让买家一件也买不到；而只返回一个 id，购物车
 * 又无从知道哪几件没下成，只能整车清空，把缺货件也一起抹掉。</p>
 *
 * <p>所以改成返回 {@link Result}：{@code orderId + orderNo + 接单件 + 缺货件[]}。
 * 购物车按 {@code acceptedProductIds} 决定清哪几行，按 {@code shortages} 生成提示。</p>
 *
 * @author 胡成溯（组长）定义；周到 2026-11-16 扩展为部分成功语义（B.2.3）
 */
public interface OrderCreatePort {

    /**
     * 按购物车明细创建订单。
     *
     * <p>实现方需要负责：合并同商品明细、逐件校验商品是否可售、逐件扣减库存
     * （缺货件跳过、不整单回滚）、生成订单头与明细、写入下单时的联系方式与地址快照。</p>
     *
     * @param buyerId 下单买家
     * @param lines   明细（商品 + 数量 + 购物车里的参考单价）
     * @return 下单结果；全部缺货时抛 {@code BusinessException}
     */
    Result createOrder(Long buyerId, List<CartCheckoutLine> lines);

    /**
     * 下单结果。
     *
     * @param orderId            新订单 id
     * @param orderNo            订单号（页面展示用，如 {@code SO20261116000012}）
     * @param acceptedProductIds 真正下成单的商品 id（购物车据此清行）
     * @param shortages          被拦下的商品（购物车据此保留行并提示）
     */
    record Result(Long orderId, String orderNo,
                  List<Long> acceptedProductIds, List<Shortage> shortages) {

        public Result {
            acceptedProductIds = acceptedProductIds == null
                    ? List.of() : List.copyOf(acceptedProductIds);
            shortages = shortages == null ? List.of() : List.copyOf(shortages);
        }

        /** 是否存在没下成的商品 */
        public boolean partial() {
            return !shortages.isEmpty();
        }

        /** 是否全部下成 */
        public boolean acceptedAll() {
            return shortages.isEmpty();
        }

        /** 缺货件的提示文案，如「算法导论 库存不足（仅剩 1 件，想买 3 件）」，用「、」连接 */
        public String shortageText() {
            return shortages.stream()
                    .map(Shortage::text)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
        }
    }

    /**
     * 被拦下的一件。
     *
     * @param productId   商品 id
     * @param productName 商品名（下单时刻的名字，用于提示）
     * @param requested   想买几件
     * @param available   当时可售几件（下架为 0）
     * @param reason      「库存不足」/「已下架」/「商品不存在」
     */
    record Shortage(Long productId, String productName,
                    int requested, int available, String reason) {

        /** 给买家看的一句话 */
        public String text() {
            String name = productName == null || productName.isBlank()
                    ? "商品 " + productId : productName;
            if ("库存不足".equals(reason)) {
                return name + " 库存不足（仅剩 " + available + " 件，想买 " + requested + " 件）";
            }
            return name + " " + reason;
        }
    }
}

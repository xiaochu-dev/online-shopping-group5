package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下单提供方（T4-06）：购物车结算与商品详情页单件下单的<b>唯一实现</b>。
 *
 * <h2>一条链路，两个入口</h2>
 * <p>迭代 3 之前有两个下单入口：「提交意向」（单件）和「交易成功扣库存」（卖家点）。
 * B 之后它们必须收敛到同一条链路，否则同一张 {@code intent} 表里会混进两种语义的订单——
 * 一种扣过库存、一种没扣，取消时回补就会把库存弄多。</p>
 *
 * <p>所以 {@link com.group5.shop.portal.service.PortalService#submitOrder} 也改成调这里。
 * 现在「下单即扣库存」只有一处实现，页面上从哪进来都一样。</p>
 *
 * <h2>缺货为什么逐件处理</h2>
 * <p>11-15 答复 Q3：多件下单逐件校验，缺货件提示、其余照常下单、不拆单。
 * 所以这里先按件挑出「商品不存在 / 已下架」，再交给 {@link StockBatchPort#deduct} 逐件扣，
 * 只有「一件都下成不了」时才整体报错——那时建一张空订单毫无意义。</p>
 *
 * <h2>订单头为什么只放首件商品</h2>
 * <p>{@code intent.product_id} 是迭代 1 留下的单值列（NOT NULL），完整明细在
 * {@code order_item}。这里填「第一件」，用于卖家页的列表展示与老查询兼容。
 * 相应地，{@link OrderStatusService} 的卖家归属校验改为「订单内任一件商品属于该卖家」，
 * 避免多卖家合并下单时出现谁都推不动的死单。</p>
 *
 * <h2>价格用下单时刻的</h2>
 * <p>{@link CartCheckoutLine#unitPriceCents()} 是加购时看到的价格，可能已经过期。
 * 成交与明细快照都用 {@code product.price_cents} 现值，避免「购物车放了一周再用旧价买」。</p>
 *
 * @author 周到（迭代 4 · T4-06）
 */
@Service
public class OrderCreateProvider implements OrderCreatePort {

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final IntentMapper intentMapper;
    private final OrderLineMapper orderLineMapper;
    private final ProductMapper productMapper;
    private final BuyerMapper buyerMapper;
    private final StockBatchPort stockBatchPort;

    public OrderCreateProvider(IntentMapper intentMapper,
                               OrderLineMapper orderLineMapper,
                               ProductMapper productMapper,
                               BuyerMapper buyerMapper,
                               StockBatchPort stockBatchPort) {
        this.intentMapper = intentMapper;
        this.orderLineMapper = orderLineMapper;
        this.productMapper = productMapper;
        this.buyerMapper = buyerMapper;
        this.stockBatchPort = stockBatchPort;
    }

    // ==================================================================
    // 端口实现：购物车结算
    // ==================================================================

    /**
     * 购物车结算下单。
     *
     * <p>地址取买家资料里的默认交易地点（购物车页面没有地址输入框）。
     * 没填过的会被拦下并提示去补——A.2 已规定下单必须有交付地点。</p>
     */
    @Override
    @Transactional
    public Result createOrder(Long buyerId, List<CartCheckoutLine> lines) {
        return doCreate(buyerId, lines, null);
    }

    // ==================================================================
    // 单件入口：商品详情页「提交意向」复用同一条链路
    // ==================================================================

    /**
     * 单件下单（商品详情页入口），可指定本次交易地点。
     *
     * <p>不是端口方法：购物车用不到「每单填一次地址」，但详情页的旧表单有这个输入框，
     * 保留它可以让买家临时改交付地点而不必先去改资料。</p>
     *
     * @param buyerId  买家 id
     * @param lines    明细（单件入口固定一条）
     * @param address  本次交易地点；为空则用买家默认地点
     * @return 下单结果
     */
    @Transactional
    public Result createOrder(Long buyerId, List<CartCheckoutLine> lines, String address) {
        return doCreate(buyerId, lines, address);
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    private Result doCreate(Long buyerId, List<CartCheckoutLine> lines, String addressOverride) {
        if (buyerId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "请先登录后再下单");
        }
        Buyer buyer = buyerMapper.selectById(buyerId);
        if (buyer == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "买家不存在，请重新登录");
        }

        Map<Long, Integer> merged = merge(lines);
        if (merged.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "没有可下单的商品");
        }

        // 第一轮：按件判断「商品还在不在、还能不能卖」。这一轮不下任何库存
        List<Shortage> shortages = new ArrayList<>();
        Map<Long, Product> sellable = new LinkedHashMap<>();
        List<StockBatchPort.Demand> demands = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            Product product = productMapper.selectById(productId);
            if (product == null) {
                shortages.add(new Shortage(productId, null, quantity, 0, "商品不存在"));
                continue;
            }
            if (!ProductStatus.ON_SALE.equals(product.getStatus())) {
                shortages.add(new Shortage(productId, product.getName(), quantity, 0, "已下架"));
                continue;
            }
            sellable.put(productId, product);
            demands.add(new StockBatchPort.Demand(productId, quantity));
        }

        // 全都不卖：不必建空订单，直接把每件的原因带回给页面
        if (demands.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "所选商品都下不了单：" + joinShortageText(shortages));
        }

        // 地址先落定，再动库存：地址没填是「还没走到下单」的状态，
        // 不该先去扣一趟再回滚——回滚虽然安全，但没必要让并发窗口多开一次
        String address = resolveAddress(buyer, addressOverride);

        // 第二轮：逐件扣库存。缺货件不进订单，但也不影响其余件
        StockBatchPort.DeductResult deducted = stockBatchPort.deduct(demands);
        for (StockBatchPort.Shortage shortage : deducted.shortages()) {
            Product product = sellable.get(shortage.productId());
            shortages.add(new Shortage(shortage.productId(),
                    product == null ? null : product.getName(),
                    shortage.requested(), shortage.available(), shortage.reason()));
        }

        Map<Long, Integer> accepted = new LinkedHashMap<>();
        for (StockBatchPort.Demand demand : deducted.deducted()) {
            accepted.put(demand.productId(), demand.quantity());
        }
        if (accepted.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "所选商品都下不了单：" + joinShortageText(shortages));
        }

        // 订单头
        long now = TimeUtils.nowMillis();
        Intent intent = new Intent();
        intent.setProductId(accepted.keySet().iterator().next());
        intent.setBuyerId(buyerId);
        intent.setBuyerName(blankToEmpty(buyer.getUsername()));
        intent.setPhone(blankToEmpty(buyer.getPhone()));
        intent.setTradeAddress(address);
        intent.setStatus(IntentStatus.CREATED);
        intent.setCreatedAt(now);
        intentMapper.insert(intent);

        // 订单号用 id 拼，天然唯一；先插再补号，避免「先生成号后插入失败」留下号段空洞
        String orderNo = "SO" + LocalDate.now().format(ORDER_NO_DATE)
                + String.format("%06d", intent.getId());
        intentMapper.update(null, Wrappers.<Intent>lambdaUpdate()
                .eq(Intent::getId, intent.getId())
                .set(Intent::getOrderNo, orderNo));

        // 明细：名称与单价都写下单时刻的快照
        for (Map.Entry<Long, Integer> entry : accepted.entrySet()) {
            Product product = sellable.get(entry.getKey());
            OrderLine line = new OrderLine();
            line.setIntentId(intent.getId());
            line.setProductId(entry.getKey());
            line.setProductName(blankToEmpty(product.getName()));
            line.setPriceCents(product.getPriceCents() == null
                    ? 0 : Math.toIntExact(product.getPriceCents()));
            line.setQuantity(entry.getValue());
            line.setCreatedAt(now);
            orderLineMapper.insert(line);
        }

        return new Result(intent.getId(), orderNo,
                new ArrayList<>(accepted.keySet()), shortages);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /**
     * 合并同一商品的多次出现（数量相加）。
     *
     * <p>购物车已经用唯一索引保证「同买家同商品一行」，但单件入口与将来的其它调用方
     * 不保证这一点。合并放在这里，{@code order_item} 的唯一索引就不会被打到。</p>
     */
    private Map<Long, Integer> merge(List<CartCheckoutLine> lines) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        if (lines == null) {
            return merged;
        }
        for (CartCheckoutLine line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                continue;
            }
            merged.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return merged;
    }

    /**
     * 交易地点：本次填的优先，否则用注册时的默认地点。
     *
     * <p>都为空直接拒绝。绝不能把空地址写进订单——卖家拿到一条没有交付地点的订单
     * 既发不了货也说不清，最后只能人工清库（A.2 的结论）。</p>
     */
    private String resolveAddress(Buyer buyer, String addressOverride) {
        String address = StringUtils.hasText(addressOverride)
                ? addressOverride.trim()
                : blankToEmpty(buyer.getDefaultPlace()).trim();
        if (address.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "请先填写交易地点（可在个人资料的「默认交易地点」里预先填好）");
        }
        return address;
    }

    private String joinShortageText(List<Shortage> shortages) {
        return shortages.stream()
                .map(Shortage::text)
                .reduce((a, b) -> a + "、" + b)
                .orElse("库存不足");
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}

package com.group5.shop.portal.service;

import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.OrderItem;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的订单」查询（T2-17；迭代 4 改为五态口径）。
 *
 * <p><b>两个区块</b>：</p>
 * <ul>
 *   <li><b>进行中</b>：created / confirmed / prepared / shipped，不分页。
 *       B.1.2 要求「客户在发货前可以取消」，客户必须先看得到自己的单；</li>
 *   <li><b>已结束</b>：completed / cancelled，按结束时间倒序分页。</li>
 * </ul>
 *
 * <p>商品名与图片用<b>当前</b>商品数据：本迭代没有物理删除商品（有在途订单只能下架），
 * 商品不会消失，因此不存在「历史单显示不到商品」的问题。</p>
 *
 * @author 周到
 */
@Service
public class OrderHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;
    private final OrderStatusService orderStatusService;

    public OrderHistoryService(IntentMapper intentMapper, ProductMapper productMapper,
                               OrderStatusService orderStatusService) {
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
        this.orderStatusService = orderStatusService;
    }

    /**
     * 某买家的历史订单。
     *
     * @param buyerId  登录买家 id（调用方保证非空）
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数，默认 10，上限 50
     * @return 分页结果；无记录时 total = 0、records 为空
     */
    public PageResult<OrderItem> list(Long buyerId, Integer pageNo, Integer pageSize) {
        int page = (pageNo == null || pageNo < 1) ? 1 : pageNo;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        long total = intentMapper.countFinishedByBuyer(buyerId);
        if (total == 0) {
            return PageResult.of(List.of(), 0, page, size);
        }

        List<Intent> intents = intentMapper.selectFinishedByBuyer(
                buyerId, (long) (page - 1) * size, size);
        return PageResult.of(toItems(intents), total, page, size);
    }

    /**
     * 某买家<b>进行中</b>的意向（迭代 3 新增），供「我的订单」的「进行中」区块使用。
     *
     * <p>不分页：一个人同时挂着的未结束意向通常只有个位数，单独成块比分页更直观，
     * 也避免「进行中的单被翻页埋掉」。</p>
     *
     * @param buyerId 登录买家 id（调用方保证非空）
     * @return 进行中的意向，按提交时间正序；无则空列表
     */
    public List<OrderItem> activeList(Long buyerId) {
        return toItems(intentMapper.selectActiveByBuyer(buyerId));
    }

    /**
     * 客户取消自己的订单（B.1.2）。
     *
     * <p>合法性判定全部交给 {@link OrderStatusService#cancelByBuyer}：它负责归属校验、
     * 「发货后不可取消」的关闸、以及取消后的库存回补。<b>回补是这次口径变化带来的新动作</b>，
     * 所以不能再像迭代 3 那样用一条 UPDATE 直接改状态——那样库存不会跟着回来。</p>
     *
     * @param buyerId  当前登录买家 id
     * @param intentId 订单 id
     * @return true = 取消成功；false = 不存在 / 非本人 / 状态不可取消
     */
    public boolean cancel(Long buyerId, Long intentId) {
        if (buyerId == null || intentId == null) {
            return false;
        }
        try {
            orderStatusService.cancelByBuyer(intentId, buyerId, null);
            return true;
        } catch (BusinessException e) {
            // 不区分「不存在」与「不可取消」：避免用返回差异探测别人的单号
            return false;
        }
    }

    /**
     * 批量补商品信息。
     *
     * <p>用一次 {@code selectBatchIds} 而不是逐行查库：一页 10 条就是 10 次往返，
     * SQLite 单连接下会明显变慢。</p>
     */
    private List<OrderItem> toItems(List<Intent> intents) {
        List<OrderItem> items = new ArrayList<>(intents.size());
        if (intents.isEmpty()) {
            return items;
        }

        List<Long> productIds = intents.stream().map(Intent::getProductId).distinct().toList();
        Map<Long, Product> productMap = new HashMap<>();
        for (Product product : productMapper.selectBatchIds(productIds)) {
            productMap.put(product.getId(), product);
        }

        for (Intent intent : intents) {
            Product product = productMap.get(intent.getProductId());
            items.add(new OrderItem(intent,
                    product == null ? "（商品已不可见）" : product.getName(),
                    product == null ? "images/placeholder.png" : product.getImagePath(),
                    product == null ? null : product.getPriceCents()));
        }
        return items;
    }
}

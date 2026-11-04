package com.group5.shop.portal.service;

import com.group5.shop.common.api.PageResult;
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
 * 「我的订单」查询（T2-17）。
 *
 * <p><b>只收已结束的单</b>（success / failed），按交易时间倒序分页。这是澄清结论里
 * 明确取舍的结果：客户看不到排队位次、也不能自助取消，所以「还没出结果的单」
 * 干脆不进历史列表，避免出现「一直显示排队中却永远不动」的困惑。下单成功页会明确提示
 * 「已提交，等待卖家确认」，补上这段反馈。</p>
 *
 * <p>商品名与图片用<b>当前</b>商品数据：本迭代没有商品删除功能，商品不会消失；
 * 名称与图片也不可编辑（只能改库存），因此不存在「历史单显示新名字」的问题。</p>
 *
 * @author 周到
 */
@Service
public class OrderHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final IntentMapper intentMapper;
    private final ProductMapper productMapper;

    public OrderHistoryService(IntentMapper intentMapper, ProductMapper productMapper) {
        this.intentMapper = intentMapper;
        this.productMapper = productMapper;
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
     * 买家撤销自己的意向（迭代 3 新增）。
     *
     * <p>撤销的合法性完全交给 SQL 的 WHERE 条件判定（本人 + 仅排队中），
     * 服务层不先查再改——先查后改在并发下存在「查的时候还是 pending、改的时候已被卖家确认」
     * 的窗口，直接带条件 UPDATE 让数据库原子地判定。</p>
     *
     * @param buyerId  当前登录买家 id
     * @param intentId 意向 id
     * @return true = 撤销成功；false = 不存在 / 非本人 / 状态已不可撤销
     */
    public boolean cancel(Long buyerId, Long intentId) {
        if (buyerId == null || intentId == null) {
            return false;
        }
        return intentMapper.cancelByIdAndBuyer(intentId, buyerId) > 0;
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

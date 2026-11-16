package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.portal.contract.CategoryNode;
import com.group5.shop.portal.contract.CategoryPort;
import com.group5.shop.portal.contract.ProductPort;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.ProductView;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家门户业务（T2-13 ~ T2-16）。
 *
 * <p>本类负责：<b>在售商品列表与筛选</b>、<b>商品详情视图</b>、<b>提交购买意向</b>，
 * 以及基线兜底的<b>凭口令码撤销</b>。卖家的交易确认（T2-18）与客户的订单历史（T2-17）
 * 分别在 {@link SellerIntentService} 与 {@link OrderHistoryService}。</p>
 *
 * <h2>四条业务铁律（改动前请先读）</h2>
 * <ol>
 *   <li><b>买家只看得到在售 / 交易中的商品</b>：已下架的既不进列表，也不能提交意向。
 *       交易中（frozen）可见但不可下单——买家需要知道「这件商品还在，只是正被别人成交」；</li>
 *   <li><b>下单即扣库存</b>：迭代 4（挑战需求 B）起，提交订单时逐件扣减，
 *       不再是「卖家成交时才扣」；取消时由状态机回补（见 {@code OrderStatusService}）；</li>
 *   <li><b>允许多笔订单并存</b>：B 取消「同一买家 + 同一商品只能有一条未结束订单」的约束，
 *       复购与购物车多件下单都需要它；防连点交给应用层与页面，不再靠唯一索引；</li>
 *   <li><b>下单只有一条链路</b>：本类的 {@link #submitOrder} 只是「单件明细」的便捷入口，
 *       真正的扣库存、建单、写明细全在 {@link OrderCreateProvider}。两条路各写一份必然走偏。</li>
 * </ol>
 *
 * @author 周到
 */
@Service
public class PortalService {

    /** 列表默认每页条数，与 API 契约一致 */
    private static final int DEFAULT_PAGE_SIZE = 12;

    /** 每页上限，与 MyBatis-Plus 分页插件的 maxLimit 一致 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductMapper productMapper;
    private final IntentMapper intentMapper;
    private final CategoryPort categoryPort;
    private final ProductPort productPort;
    private final OrderCreateProvider orderCreateProvider;

    public PortalService(ProductMapper productMapper,
                         IntentMapper intentMapper,
                         CategoryPort categoryPort,
                         ProductPort productPort,
                         OrderCreateProvider orderCreateProvider) {
        this.productMapper = productMapper;
        this.intentMapper = intentMapper;
        this.categoryPort = categoryPort;
        this.productPort = productPort;
        this.orderCreateProvider = orderCreateProvider;
    }

    // ==================================================================
    // T2-13 / T2-14 列表：分页 + 两级分类筛选 + 关键词搜索
    // ==================================================================

    /**
     * 买家商品列表（只含在售）。
     *
     * @param pageNo     页码，从 1 开始；越界时返回空列表（不自动跳回第一页）
     * @param pageSize   每页条数，传 null 或非法值走默认 12；上限 100
     * @param categoryId 分类筛选，null 表示全部。<b>选一级分类会连带筛出它下面所有二级分类的商品</b>
     * @param keyword    关键词，匹配名称或描述；null / 空串表示不筛选
     * @return 分页结果
     */
    public PageResult<Product> listOnSaleProducts(Integer pageNo, Integer pageSize,
                                                  Long categoryId, String keyword) {
        int page = normalizePage(pageNo);
        int size = normalizeSize(pageSize);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        // 一级分类 → 自身 + 全部二级分类 id；二级分类 → 只筛自己
        List<Long> categoryIds = null;
        if (categoryId != null) {
            CategoryNode node = categoryPort.find(categoryId);
            if (node == null) {
                // 传了一个已被删除/停用的分类：给出空结果，而不是「筛选失效、返回全部」
                return PageResult.of(List.of(), 0, page, size);
            }
            categoryIds = node.selfAndDescendantIds();
        }

        Page<Product> raw = productMapper.selectPage(new Page<>(page, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(Product::getStatus, ProductStatus.ON_SALE)
                        .in(categoryIds != null, Product::getCategoryId, categoryIds)
                        // 关键词同时匹配名称与描述：SQL 为 (name LIKE %kw% OR description LIKE %kw%)
                        .and(kw != null, w -> w
                                .like(Product::getName, kw)
                                .or().like(Product::getDescription, kw))
                        // 发布时间倒序；id 倒序兜底，保证同毫秒发布的商品顺序稳定
                        .orderByDesc(Product::getPublishedAt)
                        .orderByDesc(Product::getId));

        return PageResult.of(raw);
    }

    /** 启用中的两级分类树，供下拉框按 {@code <optgroup>} 渲染 */
    public List<CategoryNode> categoryTree() {
        return categoryPort.listTree();
    }

    /**
     * 分类 id → 分类名 的映射，供列表页显示商品所属分类。
     *
     * <p>取<b>全部</b>分类（含已禁用与二级）：分类被禁用后，历史上已归入该分类的商品
     * 仍然存在于列表里，若不显示名称会变成空白。</p>
     */
    public Map<Long, String> categoryNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        for (CategoryNode top : categoryPort.listTree()) {
            names.put(top.id(), top.name());
            for (CategoryNode child : top.children()) {
                names.put(child.id(), child.name());
            }
        }
        return names;
    }

    // ==================================================================
    // T2-15 详情
    // ==================================================================

    /**
     * 商品详情视图：商品本体 + 图集 + 库存 + 能否下单。
     *
     * <p>与列表不同，这里<b>放行 {@code frozen}</b>：交易中的商品买家点进来要能看到
     * 「正在交易中」并看到按钮置灰，而不是一个 404 让人以为商品没了。</p>
     *
     * @param productId 商品 id
     * @return 详情视图
     * @throws BusinessException 商品不存在或已下架
     */
    public ProductView productDetail(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null
                || (!ProductStatus.ON_SALE.equals(product.getStatus())
                && !ProductStatus.FROZEN.equals(product.getStatus()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架");
        }

        List<String> images = new ArrayList<>(productPort.imagesOf(productId));
        if (images.isEmpty()) {
            // 多图未就绪或无图：回落主图，页面永远有图可显示
            images.add(product.getImagePath());
        }

        Integer stock = productPort.stockOf(productId);
        boolean frozen = ProductStatus.FROZEN.equals(product.getStatus());
        boolean soldOut = stock != null && stock <= 0;

        return new ProductView(product, images, stock, frozen, soldOut);
    }

    /**
     * 某商品当前<b>进行中的订单数</b>（页面显示「当前有 N 张进行中订单」）。
     *
     * <p>迭代 4 起没有排队概念：B 的订单是下单即扣库存、按状态推进，
     * 所以这里数的是「还没走到终局的订单」，不是排队位次。</p>
     */
    public int activeOrderCount(Long productId) {
        return intentMapper.selectActiveByProduct(productId).size();
    }

    // ==================================================================
    // T2-16 提交意向（必须登录）
    // ==================================================================

    /**
     * 提交订单（商品详情页入口）：单件明细的便捷包装。
     *
     * <p><b>迭代 4 起不再自己写库</b>：扣库存、建订单头、写明细、生成订单号全在
     * {@link OrderCreateProvider}。购物车结算走的是同一个方法，两个入口不会走出两种语义
     * （一个扣过库存、一个没扣）。</p>
     *
     * <p>这里只保留三项前置校验，因为它们要给出与页面一致的文案：商品不存在、交易中、
     * 已下架。地址与库存的最终判定仍然在提供方那边做——页面文案可以重复，规则不行。</p>
     *
     * @param buyer        登录买家（账号与客户模块的正式实体，调用方已保证非空）
     * @param productId    商品 id
     * @param tradeAddress 本单交易地点；为空时用买家默认地点
     * @return 新写入的订单
     * @throws BusinessException 商品不可下单、库存不足、或没填交易地点
     */
    @Transactional
    public Intent submitOrder(Buyer buyer, Long productId, String tradeAddress) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架，无法提交意向");
        }
        if (ProductStatus.FROZEN.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该商品正在交易中，暂时无法下单");
        }
        if (!ProductStatus.ON_SALE.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架，无法提交意向");
        }

        long referencePrice = product.getPriceCents() == null ? 0L : product.getPriceCents();
        OrderCreatePort.Result result = orderCreateProvider.createOrder(
                buyer.getId(),
                List.of(new CartCheckoutLine(productId, 1, referencePrice)),
                tradeAddress);
        return intentMapper.selectById(result.orderId());
    }

    // ==================================================================
    // 基线条目兜底：凭口令码撤销
    // ==================================================================

    /**
     * 凭口令码撤销意向（<b>买家页面已无入口</b>，保留给基线验收物与存量数据）。
     *
     * <p>「码不存在」与「码存在但状态不允许撤销」返回同一个结果，
     * 调用方据此给出<b>同一句提示</b>——否则等于告诉别人「这个码是真的，只是用过了」。</p>
     *
     * @param tokenCode 口令码
     * @return true = 撤销成功；false = 码无效或当前状态不可撤销
     */
    @Transactional
    public boolean cancelByTokenCode(String tokenCode) {
        if (!StringUtils.hasText(tokenCode)) {
            return false;
        }
        Intent intent = intentMapper.selectByTokenCode(tokenCode.trim());
        // B 的关闸口径：客户只能在开始发货之前取消（存量 pending 视同 created，仍可取消）
        if (intent == null || !IntentStatus.canBuyerCancel(IntentStatus.fromLegacy(intent.getStatus()))) {
            return false;
        }

        long now = TimeUtils.nowMillis();
        Intent patch = new Intent();
        patch.setId(intent.getId());
        patch.setStatus(IntentStatus.CANCELLED);
        patch.setCancelledBy(IntentStatus.CANCELLED_BY_BUYER);
        patch.setCancelledAt(now);
        patch.setFinishedAt(now);
        intentMapper.updateById(patch);
        return true;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 页码归一：小于 1 或为 null 时取第 1 页 */
    private int normalizePage(Integer pageNo) {
        return (pageNo == null || pageNo < 1) ? 1 : pageNo;
    }

    /** 每页条数归一：null / 非正数取默认 12，超过上限按 100 截断 */
    private int normalizeSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

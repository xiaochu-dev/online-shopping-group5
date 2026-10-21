package com.group5.shop.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.entity.Category;
import com.group5.shop.common.entity.Intent;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.mapper.CategoryMapper;
import com.group5.shop.common.mapper.IntentMapper;
import com.group5.shop.common.mapper.ProductMapper;
import com.group5.shop.common.util.TimeUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家门户业务（T2-11 ~ T2-14）。
 *
 * <p>本类只做三件事：<b>查在售商品</b>（列表 / 详情）、<b>提交意向入队</b>、<b>凭口令码撤销</b>。
 * 卖家侧的成交、冻结、递补属于迭代 3，本迭代不涉及。</p>
 *
 * <h2>四条业务铁律（改动前请先读）</h2>
 * <ol>
 *   <li><b>买家只看得到在售商品</b>：所有对外查询都带 {@code status = 'on_sale'}，
 *       下架商品既不进列表，也不接受新意向（FR-03-1）；</li>
 *   <li><b>先到先得靠「提交时间 + 自增 id」</b>：队列排序为 {@code created_at ASC, id ASC}，
 *       不落库位次字段——撤销一条后，后面的人自然前移，无需批量 UPDATE（NFR-02 / NFR-03）；</li>
 *   <li><b>同一手机号可重复提交</b>：不做号码去重，每次提交都是独立一行、独立口令码（清偿迭代 1 遗留 C-3）；</li>
 *   <li><b>只有排队中（pending）的意向可撤销</b>：已进入交易或已结束的意向，撤销提示与「口令码无效」完全一致，
 *       避免泄露「这个码其实存在」（FR-04-6）。</li>
 * </ol>
 *
 * @author 周到
 */
@Service
public class PortalService {

    /** 列表默认每页条数，与 API 契约 1.6 一致 */
    private static final int DEFAULT_PAGE_SIZE = 12;

    /** 每页上限，与 MyBatis-Plus 分页插件的 maxLimit 一致 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ProductMapper productMapper;
    private final IntentMapper intentMapper;
    private final CategoryMapper categoryMapper;

    public PortalService(ProductMapper productMapper,
                         IntentMapper intentMapper,
                         CategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.intentMapper = intentMapper;
        this.categoryMapper = categoryMapper;
    }

    // ==================================================================
    // T2-11 / T2-12 列表：分页 + 分类筛选 + 关键词搜索
    // ==================================================================

    /**
     * 买家商品列表（只含在售）。
     *
     * @param pageNo     页码，从 1 开始；越界时返回空列表（不自动跳回第一页）
     * @param pageSize   每页条数，传 null 或非法值走默认 12；上限 100
     * @param categoryId 分类筛选，null 表示全部
     * @param keyword    关键词，匹配名称或描述；null / 空串表示不筛选
     * @return 分页结果，total 为真实总数（分页插件已指定 SQLite 方言）
     */
    public PageResult<Product> listOnSaleProducts(Integer pageNo, Integer pageSize,
                                                  Long categoryId, String keyword) {
        int page = normalizePage(pageNo);
        int size = normalizeSize(pageSize);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        Page<Product> raw = productMapper.selectPage(new Page<>(page, size),
                Wrappers.<Product>lambdaQuery()
                        .eq(Product::getStatus, ProductStatus.ON_SALE)
                        .eq(categoryId != null, Product::getCategoryId, categoryId)
                        // 关键词同时匹配名称与描述：SQL 为 (name LIKE %kw% OR description LIKE %kw%)
                        .and(kw != null, w -> w
                                .like(Product::getName, kw)
                                .or().like(Product::getDescription, kw))
                        // 发布时间倒序；id 倒序兜底，保证同毫秒发布的商品顺序稳定
                        .orderByDesc(Product::getPublishedAt)
                        .orderByDesc(Product::getId));

        return PageResult.of(raw);
    }

    /** 分类下拉框：只取启用中的分类，已按 sort_no 升序 */
    public List<Category> listEnabledCategories() {
        return categoryMapper.selectEnabled();
    }

    /**
     * 分类 id → 分类名 的映射，供列表页显示商品所属分类。
     *
     * <p>取<b>全部</b>分类（含已禁用）：分类被禁用后，历史上已归入该分类的商品
     * 仍然存在于列表里，若不显示名称会变成空白。禁用只影响下拉框能否再选到它。</p>
     */
    public Map<Long, String> listCategoryNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        for (Category category : categoryMapper.selectList(null)) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    // ==================================================================
    // T2-13 详情
    // ==================================================================

    /**
     * 取在售商品详情。
     *
     * @param productId 商品 id
     * @return 商品
     * @throws BusinessException 商品不存在或已下架（统一提示，不区分两者）
     */
    public Product requireOnSaleProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !ProductStatus.ON_SALE.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架");
        }
        return product;
    }

    /** 取商品当前排队人数（页面显示「已有 N 人排队」，队列长度即意向数） */
    public int queueSize(Long productId) {
        return intentMapper.selectQueue(productId).size();
    }

    // ==================================================================
    // T2-13 提交意向（提交即入队）
    // ==================================================================

    /**
     * 提交购买意向：写入一条 {@code pending} 记录，并生成口令码。
     *
     * <p>不做号码去重、不查重，同一买家连续提交三次就得到三条独立记录、三个独立口令码。</p>
     *
     * @param productId 商品 id
     * @param buyerName 姓名（必填，去空格后非空）
     * @param phone     电话（必填，去空格后非空；不校验格式）
     * @return 新写入的意向，{@code tokenCode} 为本次生成的口令码
     * @throws BusinessException 商品不存在/已下架，或姓名电话为空
     */
    @Transactional
    public Intent submitIntent(Long productId, String buyerName, String phone) {
        // 1. 商品必须仍在售——列表页停留期间商品可能已被卖家下架
        Product product = productMapper.selectById(productId);
        if (product == null || !ProductStatus.ON_SALE.equals(product.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架，无法提交意向");
        }

        // 2. 姓名与电话必填（不校验手机号格式，需求明确「只校验非空」）
        String name = buyerName == null ? "" : buyerName.trim();
        String tel = phone == null ? "" : phone.trim();
        if (name.isEmpty() || tel.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "姓名与联系电话均为必填项");
        }

        // 3. 写意向：status 固定 pending，created_at 取毫秒，队列按它排序
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerName(name);
        intent.setPhone(tel);
        intent.setTokenCode(TimeUtils.randomTokenCode());
        intent.setStatus(IntentStatus.PENDING);
        intent.setCreatedAt(TimeUtils.nowMillis());
        intentMapper.insert(intent);
        return intent;
    }

    // ==================================================================
    // T2-14 凭口令码撤销
    // ==================================================================

    /**
     * 凭口令码撤销意向。
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
        if (intent == null || !IntentStatus.cancellable(intent.getStatus())) {
            return false;
        }

        // 队列位次不落库：把状态改为 cancelled，后面排队的意向查询时自然前移
        Intent patch = new Intent();
        patch.setId(intent.getId());
        patch.setStatus(IntentStatus.CANCELLED);
        intentMapper.updateById(patch);
        return true;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 页码归一：小于 1 或为 null 时取第 1 页 */
    private int normalizePage(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    /** 每页条数归一：null / 非正数取默认 12，超过上限按 100 截断 */
    private int normalizeSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}

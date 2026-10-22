package com.group5.shop.product.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group5.shop.product.domain.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商品 Mapper（T2-06）。
 *
 * <p>三人共用。以下是各模块的典型用法（都基于 {@link BaseMapper}，不需要额外写 SQL）：</p>
 *
 * <pre>{@code
 * // 黄程宇：我的商品（T2-10）
 * productMapper.selectList(Wrappers.<Product>lambdaQuery()
 *         .eq(Product::getSellerId, sellerId)
 *         .orderByDesc(Product::getPublishedAt));
 *
 * // 周到：买家列表 + 分类筛选 + 关键词搜索 + 分页（T2-11 / T2-12）
 * Page<Product> page = new Page<>(pageNo, pageSize);
 * productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
 *         .eq(Product::getStatus, ProductStatus.ON_SALE)
 *         .eq(categoryId != null, Product::getCategoryId, categoryId)
 *         .and(StringUtils.hasText(keyword), w -> w
 *                 .like(Product::getName, keyword)
 *                 .or().like(Product::getDescription, keyword))
 *         .orderByDesc(Product::getPublishedAt));
 * }</pre>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 按状态统计商品数（后台首页概览用）。
     *
     * @param status 见 {@link com.group5.shop.common.constant.ProductStatus}
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM product WHERE status = #{status} OR (#{status} = 'delisted' AND status = 'off_shelf')")
    long countByStatus(@Param("status") String status);

    /**
     * 按卖家 + 状态统计商品数。
     *
     * @param sellerId 卖家 id
     * @param status   商品状态
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM product WHERE seller_id = #{sellerId} AND (status = #{status} OR (#{status} = 'delisted' AND status = 'off_shelf'))")
    long countBySellerIdAndStatus(@Param("sellerId") Long sellerId, @Param("status") String status);
}

package com.group5.shop.cart.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 购物车 Mapper（挑战需求 B.2.2 / B.2.3，owner：胡成溯）。
 *
 * <p><b>所有带条件的写操作都显式带 {@code buyer_id}</b>：
 * 「只能动自己的条目」不靠上层 if 判断，而是压进 SQL 的 where 条件，
 * 这样即使将来某处漏判，库层也不会改到别人的行。返回值是受影响行数，
 * 0 行即视为「不存在或不属于我」，对外不区分两者（避免用差异探测别人的条目 id）。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {

    /** 查某个买家对某个商品的购物车行（用于「重复加购累加」） */
    @Select("SELECT * FROM cart WHERE buyer_id = #{buyerId} AND product_id = #{productId}")
    Cart selectByBuyerAndProduct(@Param("buyerId") Long buyerId,
                                 @Param("productId") Long productId);

    /** 购物车列表：最近改动的排在最前 */
    @Select("SELECT * FROM cart WHERE buyer_id = #{buyerId} ORDER BY updated_at DESC, id DESC")
    List<Cart> selectByBuyer(@Param("buyerId") Long buyerId);

    /** 买家购物车条目数，用于页面角标与测试断言 */
    @Select("SELECT COUNT(*) FROM cart WHERE buyer_id = #{buyerId}")
    long countByBuyer(@Param("buyerId") Long buyerId);

    /**
     * 改数量：{@code buyer_id} 同时作为过滤条件，改不到别人的行。
     *
     * @return 受影响行数（0 = 不存在或不属于该买家）
     */
    @Update("UPDATE cart SET quantity = #{quantity}, updated_at = #{now} "
            + "WHERE id = #{id} AND buyer_id = #{buyerId}")
    int updateQuantity(@Param("id") Long id,
                       @Param("buyerId") Long buyerId,
                       @Param("quantity") Integer quantity,
                       @Param("now") Long now);

    /**
     * 删除单条。
     *
     * @return 受影响行数（0 = 不存在或不属于该买家）
     */
    @Delete("DELETE FROM cart WHERE id = #{id} AND buyer_id = #{buyerId}")
    int deleteByIdAndBuyer(@Param("id") Long id, @Param("buyerId") Long buyerId);

    /** 结算成功后清掉已下单的条目 */
    @Delete("DELETE FROM cart WHERE buyer_id = #{buyerId} AND id IN (${ids})")
    int deleteByBuyerAndIds(@Param("buyerId") Long buyerId, @Param("ids") String ids);

    /** 清空购物车 */
    @Delete("DELETE FROM cart WHERE buyer_id = #{buyerId}")
    int deleteByBuyer(@Param("buyerId") Long buyerId);
}

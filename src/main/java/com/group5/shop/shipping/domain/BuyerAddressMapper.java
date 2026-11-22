package com.group5.shop.shipping.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收货地址 Mapper（挑战需求 C.2.3，owner：胡成溯）。
 *
 * <p><b>所有写操作都带 {@code buyer_id} 条件</b>：和购物车同一套口径——
 * 「只能改自己的地址」压进 SQL，返回值 0 行即「不存在或不是你的」，对外不区分。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface BuyerAddressMapper extends BaseMapper<BuyerAddress> {

    /** 某买家的全部地址：默认在前，其余按最近修改倒序 */
    @Select("SELECT * FROM buyer_address WHERE buyer_id = #{buyerId} "
            + "ORDER BY is_default DESC, updated_at DESC, id DESC")
    List<BuyerAddress> selectByBuyer(@Param("buyerId") Long buyerId);

    /** 地址条数（首条自动设为默认时用） */
    @Select("SELECT COUNT(*) FROM buyer_address WHERE buyer_id = #{buyerId}")
    long countByBuyer(@Param("buyerId") Long buyerId);

    /** 当前默认地址（可能为空） */
    @Select("SELECT * FROM buyer_address WHERE buyer_id = #{buyerId} AND is_default = 1 LIMIT 1")
    BuyerAddress selectDefault(@Param("buyerId") Long buyerId);

    /** 取单条，附带买家校验 */
    @Select("SELECT * FROM buyer_address WHERE id = #{id} AND buyer_id = #{buyerId}")
    BuyerAddress selectOwned(@Param("id") Long id, @Param("buyerId") Long buyerId);

    /** 把该买家所有地址的默认标记清掉（设新默认前调用） */
    @Update("UPDATE buyer_address SET is_default = 0 WHERE buyer_id = #{buyerId} AND is_default = 1")
    int clearDefault(@Param("buyerId") Long buyerId);

    /** 设为默认 */
    @Update("UPDATE buyer_address SET is_default = 1, updated_at = #{now} "
            + "WHERE id = #{id} AND buyer_id = #{buyerId}")
    int markDefault(@Param("id") Long id,
                    @Param("buyerId") Long buyerId,
                    @Param("now") Long now);

    /** 删除单条，带买家校验 */
    @Delete("DELETE FROM buyer_address WHERE id = #{id} AND buyer_id = #{buyerId}")
    int deleteOwned(@Param("id") Long id, @Param("buyerId") Long buyerId);
}

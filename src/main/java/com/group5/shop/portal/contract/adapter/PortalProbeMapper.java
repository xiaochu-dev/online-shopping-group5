package com.group5.shop.portal.contract.adapter;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 临时适配层专用的只读探针 SQL（{@link SchemaCapability} 判定结构就绪后才会被调用）。
 *
 * <p><b>为什么这些 SQL 写在门户而不是商品模块</b>：它们全部是「结构就绪才执行」的读取/最小写入，
 * 目的是在队友的 Service 就位前，门户能独立跑通并演示。正式接口一交付，
 * {@link ContractDefaults} 里的 Bean 会自动退让，本 Mapper 随之不再被调用。</p>
 *
 * <p>返回 {@code Map} 而不是实体：一旦这里 import 了别人的 Entity（Product / Category / Buyer），
 * 门户就与对方的数据模型硬绑定，正是任务表第 2 条硬规则要禁止的情况。</p>
 *
 * @author 周到
 */
@Mapper
public interface PortalProbeMapper {

    /** 启用中的分类（含两级父指针），一级在前、按排序号升序 */
    @Select("SELECT id, name, parent_id, sort_no FROM category "
            + "WHERE status = 'enabled' ORDER BY sort_no ASC, id ASC")
    List<Map<String, Object>> selectEnabledCategoryRows();

    /** 某商品的全部图片路径，按排序号升序；第一张即封面 */
    @Select("SELECT file_path FROM product_image WHERE product_id = #{productId} "
            + "ORDER BY sort_no ASC, id ASC")
    List<String> selectImagePaths(@Param("productId") Long productId);

    /** 当前库存 */
    @Select("SELECT stock FROM product WHERE id = #{productId}")
    Integer selectStock(@Param("productId") Long productId);

    /** 成交扣减 1 件：条件 UPDATE 原子扣减，返回受影响行数（0 = 库存不足） */
    @Update("UPDATE product SET stock = stock - 1, updated_at = #{now} "
            + "WHERE id = #{productId} AND stock > 0")
    int deductStock(@Param("productId") Long productId, @Param("now") long now);

    /** 买家资料（下单快照来源） */
    @Select("SELECT id, username, phone, default_place FROM buyer WHERE id = #{buyerId}")
    Map<String, Object> selectBuyer(@Param("buyerId") Long buyerId);
}

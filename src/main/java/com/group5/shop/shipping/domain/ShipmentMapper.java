package com.group5.shop.shipping.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 发货记录 Mapper（挑战需求 C.2.1 / C.2.4，owner：胡成溯）。
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface ShipmentMapper extends BaseMapper<Shipment> {

    /** 按订单查发货记录（未发货返回 null） */
    @Select("SELECT * FROM shipment WHERE intent_id = #{intentId}")
    Shipment selectByIntent(@Param("intentId") Long intentId);

    /** 一批订单的发货记录，买家物流页批量展示时用 */
    @Select("<script>SELECT * FROM shipment WHERE intent_id IN "
            + "<foreach collection='intentIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Shipment> selectByIntents(@Param("intentIds") List<Long> intentIds);

    /** 已发货总数，用于首页/后台角标与测试断言 */
    @Select("SELECT COUNT(*) FROM shipment")
    long countAll();
}

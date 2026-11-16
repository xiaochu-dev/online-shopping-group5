package com.group5.shop.portal.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单明细 Mapper（T4-02，owner：周到）。
 *
 * <p>明细只做两件事：按订单查出来展示、下单时批量插入。
 * 明细不单独改状态——B 的流转是<b>整单推进</b>（11-15 答复 Q5），
 * 状态挂在订单头 {@code intent} 上，不做部分发货、部分取消。</p>
 *
 * @author 周到（迭代 4 · T4-02）
 */
@Mapper
public interface OrderLineMapper extends BaseMapper<OrderLine> {

    /** 全部字段，避免新增列后各处 SELECT 列表不同步 */
    String COLUMNS = "id, intent_id, product_id, product_name, price_cents, quantity, created_at";

    /**
     * 某订单的全部明细，按下单顺序（同一单同一商品只有一行）。
     *
     * @param intentId 订单头 id
     * @return 明细列表；无则返回空列表
     */
    @Select("SELECT " + COLUMNS + " FROM order_item WHERE intent_id = #{intentId} ORDER BY id ASC")
    List<OrderLine> selectByIntentId(@Param("intentId") Long intentId);

    /**
     * 一批订单的明细（列表页用，避免每行一次查询）。
     *
     * <p>卖家页一屏 20 单、我的订单一页 10 单，逐行查明细就是 20~30 次往返；
     * SQLite 单连接下这会让页面明显变慢。</p>
     *
     * @param intentIds 订单 id 列表，不能为空
     * @return 明细列表（未分组），按订单与 id 升序
     */
    @Select("<script>SELECT " + COLUMNS + " FROM order_item WHERE intent_id IN "
            + "<foreach item='id' collection='intentIds' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY intent_id ASC, id ASC</script>")
    List<OrderLine> selectByIntentIds(@Param("intentIds") List<Long> intentIds);

    /**
     * 某订单的件数合计（「共 N 件」文案用）。
     *
     * @param intentId 订单头 id
     * @return 数量合计；无明细返回 0
     */
    @Select("SELECT COALESCE(SUM(quantity), 0) FROM order_item WHERE intent_id = #{intentId}")
    int sumQuantity(@Param("intentId") Long intentId);
}

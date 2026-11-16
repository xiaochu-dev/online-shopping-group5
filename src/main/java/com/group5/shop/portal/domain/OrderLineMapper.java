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
     * 某订单的件数合计（「共 N 件」文案用）。
     *
     * @param intentId 订单头 id
     * @return 数量合计；无明细返回 0
     */
    @Select("SELECT COALESCE(SUM(quantity), 0) FROM order_item WHERE intent_id = #{intentId}")
    int sumQuantity(@Param("intentId") Long intentId);
}

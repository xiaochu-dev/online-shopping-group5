package com.group5.shop.payment.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 支付单 Mapper（第 5 组 · 模拟支付模块建立）。
 *
 * <p>单表 CRUD 直接用 {@link BaseMapper}；这里只补两条按业务键查询的 SQL。
 * 表 owner 是本模块，字段以 {@code schema/04-payment.sql} 为准。</p>
 *
 * @author 第 5 组（模拟支付模块）
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    /** 全部字段，避免新增列后各处 SELECT 列表不同步 */
    String COLUMNS = "id, intent_id, buyer_id, amount_cents, status, "
            + "paid_at, refunded_at, created_at, trade_no";

    /**
     * 按意向 id 查支付单（一张意向最多一张支付单）。
     *
     * @param intentId 意向 id
     * @return 支付单；不存在返回 null
     */
    @Select("SELECT " + COLUMNS + " FROM payment WHERE intent_id = #{intentId} LIMIT 1")
    Payment selectByIntentId(@Param("intentId") Long intentId);

    /**
     * 按支付流水号查支付单（模拟「网关侧按流水号对账」）。
     *
     * @param tradeNo 支付流水号
     * @return 支付单；不存在返回 null
     */
    @Select("SELECT " + COLUMNS + " FROM payment WHERE trade_no = #{tradeNo} LIMIT 1")
    Payment selectByTradeNo(@Param("tradeNo") String tradeNo);
}

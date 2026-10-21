package com.group5.shop.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group5.shop.common.entity.Intent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 购买意向 Mapper（T2-06 建立；T2-16 ~ T2-18 扩展）。
 *
 * <p>intent 表的 owner 是买家门户与交易模块（周到），本接口的所有改动都应以
 * {@code schema/03-intent.sql} 为准。</p>
 *
 * <p>队列排序规则与迭代 1 一致：<b>{@code ORDER BY created_at ASC, id ASC}</b>。
 * 主键取提交时间（毫秒），同一毫秒内的并发提交用自增 id 兜底，
 * 保证「先提交的排前面」这一语义在毫秒级并发下依然成立（NFR-03）。</p>
 *
 * <p>队列位次<b>不落库</b>：不存 position 字段，位次由查询时的行号天然算出。
 * 这样一条意向结束或被撤销，后面的人自动前移，不需要批量 UPDATE。</p>
 *
 * @author 胡成溯（组长）建立；周到扩展 T2-16 ~ T2-18
 */
@Mapper
public interface IntentMapper extends BaseMapper<Intent> {

    /** 全部字段，避免新增列后各处 SELECT 列表不同步 */
    String COLUMNS = "id, product_id, buyer_id, buyer_name, phone, trade_address, "
            + "token_code, status, trade_result, fail_reason, created_at, finished_at";

    /**
     * 某商品排队中的意向（卖家意向管理页的队列区）。
     *
     * @param productId 商品 id
     * @return 按提交时间正序；无数据返回空列表
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE product_id = #{productId} AND status = 'pending' "
            + "ORDER BY created_at ASC, id ASC")
    List<Intent> selectQueue(@Param("productId") Long productId);

    /**
     * 某商品所有<b>未结束</b>的意向（排队中 + 交易中）。
     *
     * <p>T2-18 的页面需要把「正在交易的那一笔」和后面排队的人放在同一屏，
     * 否则卖家看不到自己正在处理谁。</p>
     *
     * @param productId 商品 id
     * @return 按提交时间正序
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE product_id = #{productId} AND status IN ('pending', 'trading') "
            + "ORDER BY created_at ASC, id ASC")
    List<Intent> selectActiveQueue(@Param("productId") Long productId);

    /**
     * 按口令码查意向（基线兜底：存量单仍可凭码撤销）。
     *
     * @param tokenCode 口令码
     * @return 意向；不存在返回 null
     */
    @Select("SELECT " + COLUMNS + " FROM intent WHERE token_code = #{tokenCode} LIMIT 1")
    Intent selectByTokenCode(@Param("tokenCode") String tokenCode);

    /**
     * 统计某买家在某商品上<b>未结束</b>的意向数（T2-16 的重复提交限制）。
     *
     * <p>库层还有部分唯一索引 {@code uq_intent_buyer_active} 兜底，
     * 这里的查询用于给出友好提示，索引负责拦住并发穿透。</p>
     *
     * @param buyerId   买家 id
     * @param productId 商品 id
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE buyer_id = #{buyerId} AND product_id = #{productId} "
            + "AND status IN ('pending', 'trading')")
    int countActiveByBuyer(@Param("buyerId") Long buyerId, @Param("productId") Long productId);

    /**
     * 某买家<b>已结束</b>的订单（T2-17 我的订单）。
     *
     * <p>只取 success / failed：排队中、交易中的单还没出结果，不属于「历史」，
     * 也不进这个列表（澄清结论）。按交易时间倒序，交易时间相同用 id 兜底保证稳定。</p>
     *
     * @param buyerId 买家 id
     * @param offset  偏移量
     * @param limit   每页条数
     * @return 订单列表
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE buyer_id = #{buyerId} AND status IN ('success', 'failed') "
            + "ORDER BY finished_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Intent> selectFinishedByBuyer(@Param("buyerId") Long buyerId,
                                       @Param("offset") long offset,
                                       @Param("limit") long limit);

    /**
     * 某买家已结束订单总数（分页用）。
     *
     * @param buyerId 买家 id
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE buyer_id = #{buyerId} "
            + "AND status IN ('success', 'failed')")
    long countFinishedByBuyer(@Param("buyerId") Long buyerId);

    /**
     * 按商品统计未结束意向数（卖家「我的商品」列表上显示队列长度）。
     *
     * <p>一次分组查询代替「每个商品查一次」，避免列表页 N+1。</p>
     *
     * @return 每行含 {@code product_id} 与 {@code cnt}
     */
    @Select("SELECT product_id, COUNT(*) AS cnt FROM intent "
            + "WHERE status IN ('pending', 'trading') GROUP BY product_id")
    List<java.util.Map<String, Object>> countActiveGroupByProduct();

    /**
     * 按状态统计意向数。
     *
     * @param status 见 {@link com.group5.shop.common.constant.IntentStatus}
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}

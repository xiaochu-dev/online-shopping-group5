package com.group5.shop.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.group5.shop.common.entity.Intent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 购买意向 Mapper（T2-06）。
 *
 * <p>队列排序规则与迭代 1 完全一致：<b>{@code ORDER BY created_at ASC, id ASC}</b>。
 * 主键取提交时间（毫秒），同一毫秒内的并发提交用自增 id 兜底，
 * 保证「先提交的排前面」这一语义在毫秒级并发下依然成立（NFR-03）。</p>
 *
 * <p>队列位次<b>不落库</b>：不存 position 字段，位次由查询时的行号天然算出。
 * 这样撤销一条意向，后面的人自动前移，不需要批量 UPDATE（NFR-02）。</p>
 *
 * @author 胡成溯（组长）
 */
@Mapper
public interface IntentMapper extends BaseMapper<Intent> {

    /**
     * 查询某商品的排队中意向（队列）。
     *
     * @param productId 商品 id
     * @return 按提交时间正序排列的意向；无数据返回空列表
     */
    @Select("SELECT id, product_id, buyer_name, phone, token_code, status, created_at "
            + "FROM intent WHERE product_id = #{productId} AND status = 'pending' "
            + "ORDER BY created_at ASC, id ASC")
    List<Intent> selectQueue(@Param("productId") Long productId);

    /**
     * 按口令码查意向（买家撤销时用）。
     *
     * @param tokenCode 口令码
     * @return 意向；不存在返回 null
     */
    @Select("SELECT id, product_id, buyer_name, phone, token_code, status, created_at "
            + "FROM intent WHERE token_code = #{tokenCode} LIMIT 1")
    Intent selectByTokenCode(@Param("tokenCode") String tokenCode);

    /**
     * 按状态统计意向数。
     *
     * @param status 见 {@link com.group5.shop.common.constant.IntentStatus}
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}

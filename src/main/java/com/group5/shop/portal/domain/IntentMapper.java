package com.group5.shop.portal.domain;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 订单头 Mapper（T2-06 建立；T4-01 / T4-03 改为五态口径）。
 *
 * <p>{@code intent} 表的 owner 是订单域（周到），本接口的所有改动都以
 * {@code schema/03-intent.sql} 为准。</p>
 *
 * <h2>迭代 4 的口径变化</h2>
 * <ul>
 *   <li><b>队列查询整体删除</b>：没有 {@code selectQueue}（待处理队列）了，
 *       取而代之的是「某商品的全部订单 + 按状态筛选」；</li>
 *   <li><b>「未结束」= created / confirmed / prepared / shipped</b>；
 *       「已结束」= completed / cancelled；</li>
 *   <li><b>状态写入不在这里</b>：除了「按口令码作废存量单」这条基线兜底，
 *       所有状态变更走 {@code OrderStatusService}，避免规则散落到 SQL 里。</li>
 * </ul>
 *
 * @author 胡成溯（组长）建立；周到扩展 T2-16 ~ T2-18、T4-01 ~ T4-04
 */
@Mapper
public interface IntentMapper extends BaseMapper<Intent> {

    /** 全部字段，避免新增列后各处 SELECT 列表不同步 */
    String COLUMNS = "id, order_no, product_id, buyer_id, buyer_name, phone, trade_address, "
            + "token_code, status, cancelled_by, cancelled_at, trade_result, fail_reason, "
            + "created_at, finished_at";

    /** 未结束（进行中）的状态集合，直接拼进 SQL，避免各处抄错 */
    String ACTIVE_STATUSES = "('created', 'confirmed', 'prepared', 'shipped')";

    /** 已结束（终局）的状态集合 */
    String FINISHED_STATUSES = "('completed', 'cancelled')";

    /**
     * 「这张订单里含某商品」的判定（B.2.3 一张单可含多件）。
     *
     * <p>不能只看 {@code intent.product_id}：那一列是迭代 1 的单值遗留，多件下单时只存首件，
     * 于是「算法导论 + 机械键盘」一起结算的单，在机械键盘的订单页里查不到。
     * 所以先认明细 {@code order_item}，再用 {@code product_id} 兜住没有明细的老意向单。</p>
     */
    String CONTAINS_PRODUCT = "(product_id = #{productId} OR EXISTS ("
            + "SELECT 1 FROM order_item oi WHERE oi.intent_id = intent.id "
            + "AND oi.product_id = #{productId}))";

    /**
     * 某商品的订单列表，可按状态筛选（T4-04 卖家页）。
     *
     * <p>口径是「<b>含</b>该商品的订单」，不是「首件商品正好是该商品的订单」——
     * 见 {@link #CONTAINS_PRODUCT}。多件订单因此在每个相关商品的页面都能查到，
     * 但仍是<b>同一张单</b>（同一个订单号、同一套状态）。</p>
     *
     * @param productId 商品 id
     * @param status    状态值；null / 空串表示不筛选
     * @return 按下单时间倒序；无数据返回空列表
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE " + CONTAINS_PRODUCT + " "
            + "AND (#{status} IS NULL OR #{status} = '' OR status = #{status}) "
            + "ORDER BY created_at DESC, id DESC")
    List<Intent> selectByProduct(@Param("productId") Long productId,
                                 @Param("status") String status);

    /**
     * 某商品<b>未结束</b>的订单（created / confirmed / prepared / shipped）。
     *
     * <p>商品域用它判断「这个商品还有没有在途订单」（如库存调 0 前的保护）。
     * 同样按「含该商品」判定，否则多件订单里的非首件商品会被当成没有在途单。</p>
     *
     * @param productId 商品 id
     * @return 按下单时间正序
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE " + CONTAINS_PRODUCT + " AND status IN " + ACTIVE_STATUSES + " "
            + "ORDER BY created_at ASC, id ASC")
    List<Intent> selectActiveByProduct(@Param("productId") Long productId);

    /**
     * 某商品的订单数按状态分组（筛选标签计数）。
     *
     * <p>与 {@link #selectByProduct} 同一口径，否则会出现「列表里有 1 张单、
     * 标签上写 0 张」的自相矛盾。</p>
     *
     * @param productId 商品 id
     * @return 每行含 {@code status} 与 {@code cnt}
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM intent "
            + "WHERE " + CONTAINS_PRODUCT + " GROUP BY status")
    List<Map<String, Object>> countByProductGroupByStatus(@Param("productId") Long productId);

    /**
     * 按口令码查订单（基线兜底：存量单仍可凭码撤销）。
     *
     * @param tokenCode 口令码
     * @return 订单；不存在返回 null
     */
    @Select("SELECT " + COLUMNS + " FROM intent WHERE token_code = #{tokenCode} LIMIT 1")
    Intent selectByTokenCode(@Param("tokenCode") String tokenCode);

    /**
     * 统计某买家在某商品上<b>未结束</b>的订单数。
     *
     * <p>迭代 1~3 用这条查询 + 部分唯一索引实现「一人一商品只能有一条未结束意向」；
     * B 取消了该约束（允许多件下单与复购），此处只保留查询供业务侧给友好提示。</p>
     *
     * @param buyerId   买家 id
     * @param productId 商品 id
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE buyer_id = #{buyerId} AND product_id = #{productId} "
            + "AND status IN " + ACTIVE_STATUSES)
    int countActiveByBuyer(@Param("buyerId") Long buyerId, @Param("productId") Long productId);

    /**
     * 某买家<b>已结束</b>的订单（我的订单 · 已结束区块）。
     *
     * @param buyerId 买家 id
     * @param offset  偏移量
     * @param limit   每页条数
     * @return 按结束时间倒序
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE buyer_id = #{buyerId} AND status IN " + FINISHED_STATUSES + " "
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
            + "AND status IN " + FINISHED_STATUSES)
    long countFinishedByBuyer(@Param("buyerId") Long buyerId);

    /**
     * 某买家<b>进行中</b>的订单（我的订单 · 进行中区块）。
     *
     * <p>B 要求「客户在发货前可以取消」，所以进行中的单必须对客户可见——
     * 这与迭代 2「只展示已有结果」的口径正好相反。</p>
     *
     * @param buyerId 买家 id
     * @return 按下单时间正序
     */
    @Select("SELECT " + COLUMNS + " FROM intent "
            + "WHERE buyer_id = #{buyerId} AND status IN " + ACTIVE_STATUSES + " "
            + "ORDER BY created_at ASC, id ASC")
    List<Intent> selectActiveByBuyer(@Param("buyerId") Long buyerId);

    /**
     * 按商品统计未结束订单数（卖家「我的商品」列表上的角标）。
     *
     * <p>多件订单会给它含的<b>每个</b>商品各记 1 条——卖家在机械键盘那行也该看到
     * 「有 1 张单在途」，哪怕这张单的首件商品是算法导论。没有明细的老意向单按
     * {@code intent.product_id} 记 1 条，两者用 {@code UNION} 合并去重。</p>
     *
     * @return 每行含 {@code product_id} 与 {@code cnt}
     */
    @Select("SELECT product_id, COUNT(*) AS cnt FROM ("
            + "SELECT i.id AS intent_id, oi.product_id AS product_id FROM intent i "
            + "JOIN order_item oi ON oi.intent_id = i.id "
            + "WHERE i.status IN " + ACTIVE_STATUSES + " "
            + "UNION "
            + "SELECT i.id AS intent_id, i.product_id AS product_id FROM intent i "
            + "WHERE i.status IN " + ACTIVE_STATUSES + " "
            + "AND NOT EXISTS (SELECT 1 FROM order_item oi2 WHERE oi2.intent_id = i.id)"
            + ") GROUP BY product_id")
    List<Map<String, Object>> countActiveGroupByProduct();

    /**
     * 按状态统计订单数（后台首页与自检用）。
     *
     * @param status 见 {@link com.group5.shop.common.constant.IntentStatus}
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM intent WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}

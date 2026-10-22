package com.group5.shop.portal.contract;

import java.util.List;

/**
 * 商品中心对门户的出口（提供方：黄程宇 T2-08 / T2-10 / T2-11）。
 *
 * <p>任务表把它写成 {@code ProductDetailService.get()} + {@code StockService.deduct()} 两个接口，
 * 门户侧合成一个只含「门户真正会调的动作」的窄接口——门户不关心商品模块内部怎么分组，
 * 只关心「能不能下单 / 扣不扣得动 / 显示什么图」。</p>
 *
 * <p><b>扣减时机是本迭代最容易写错的一点</b>：买家提交意向时<b>不扣</b>库存，
 * 只有卖家点「交易成功」（T2-18 的 success 分支）才调 {@link #deduct(Long)} 扣 1 件。
 * 提交时只校验「商品在售且库存不为 0」。</p>
 *
 * <p>「整件冻结」是商品级互斥：交易中商品置 {@code frozen}，同一时刻只允许一笔进行中的交易，
 * 因此不存在超卖，也不需要预扣库存。</p>
 *
 * @author 周到
 */
public interface ProductPort {

    /**
     * 进入交易：商品置 {@code frozen}（交易中），买家端不再接受新意向。
     *
     * @param productId 商品 id
     */
    void freeze(Long productId);

    /**
     * 结束交易：商品由 {@code frozen} 恢复为 {@code on_sale}。
     *
     * <p>成交且库存归零时，商品中心会在此之后自动置为下架，本方法不负责判断。</p>
     *
     * @param productId 商品 id
     */
    void release(Long productId);

    /**
     * 成交扣减 1 件。
     *
     * @param productId 商品 id
     * @throws com.group5.shop.common.exception.BusinessException 库存不足时抛出
     */
    void deduct(Long productId);

    /**
     * 当前可售库存。
     *
     * @param productId 商品 id
     * @return 库存数；{@code null} 表示商品表还没有库存字段（T2-10 未落地），页面按「有货」显示
     */
    Integer stockOf(Long productId);

    /**
     * 商品图片路径，第一张为封面，最多 9 张。
     *
     * @param productId 商品 id
     * @return 图片路径列表；多图未就绪时返回空列表，由调用方回落到主图
     */
    List<String> imagesOf(Long productId);
}

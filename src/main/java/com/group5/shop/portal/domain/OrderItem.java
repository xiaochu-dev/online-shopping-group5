package com.group5.shop.portal.domain;

import com.group5.shop.common.entity.Intent;
import com.group5.shop.common.util.MoneyUtils;

/**
 * 「我的订单」一行（T2-17）。
 *
 * <p>只承载<b>已结束</b>的单（success / failed）。已下单但还在排队或交易中的单不进这个列表——
 * 客户看不到位次，也不允许自助取消，这是澄清结论里明确的取舍。</p>
 *
 * <p>商品名与图片取<b>当前</b>商品数据：本迭代不提供商品删除功能，商品不会消失；
 * 名称/图片也不可编辑，因此不存在「历史单显示的是新名字」的问题。</p>
 *
 * @param intent 意向单（含下单时间、交易时间、结果、失败原因、交易地点）
 * @param name   商品名
 * @param image  商品封面图路径
 * @param priceCents 下单时的商品价格（分）
 * @author 周到
 */
public record OrderItem(Intent intent, String name, String image, Long priceCents) {

    /** 价格展示：分 → 元，两位小数 */
    public String priceYuan() {
        return priceCents == null ? "—" : MoneyUtils.centsToYuan(priceCents);
    }

    /** 结果徽章样式：成功绿、失败灰 */
    public String resultCss() {
        return "success".equals(intent.getTradeResult()) ? "tag-green" : "tag-gray";
    }
}

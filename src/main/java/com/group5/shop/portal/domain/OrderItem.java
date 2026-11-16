package com.group5.shop.portal.domain;

import com.group5.shop.common.util.MoneyUtils;

import java.util.List;

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
 * @param lines      订单明细（B.2.3 多件下单后才有；老单为空列表）
 * @author 周到
 */
public record OrderItem(Intent intent, String name, String image, Long priceCents,
                        List<OrderLine> lines) {

    public OrderItem {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 明细摘要，如「机械键盘 ×2、教材 ×1」；空表示老单（页面回落成只显示首件商品） */
    public String itemsText() {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (OrderLine line : lines) {
            if (!text.isEmpty()) {
                text.append('、');
            }
            text.append(line.getProductName()).append(" ×").append(line.getQuantity());
        }
        return text.toString();
    }

    /** 件数合计；没有明细的老单按 1 件算 */
    public int totalQuantity() {
        if (lines.isEmpty()) {
            return 1;
        }
        return lines.stream().mapToInt(line -> line.getQuantity() == null ? 0 : line.getQuantity()).sum();
    }

    /** 是否多件订单 */
    public boolean multiLine() {
        return lines.size() > 1;
    }

    /** 价格展示：分 → 元，两位小数 */
    public String priceYuan() {
        return priceCents == null ? "—" : MoneyUtils.centsToYuan(priceCents);
    }

    /** 结果徽章样式：成功绿、失败灰 */
    public String resultCss() {
        return "success".equals(intent.getTradeResult()) ? "tag-green" : "tag-gray";
    }
}

package com.group5.shop.portal.domain;

import com.group5.shop.product.domain.Product;

import java.util.List;

/**
 * 买家详情页视图（T2-15）：把「商品本体 + 图片集 + 库存 + 能否下单」一次算清。
 *
 * <p><b>为什么不在模板里判断</b>：能不能下单取决于三件事——是否在售、是否交易中、
 * 库存是否为 0。三个条件分散在模板里写 {@code th:if} 极易漏掉一种组合，
 * 页面就会露出「明明售罄却能点」的按钮。放在 Java 里算成布尔值，模板只负责显示。</p>
 *
 * @param product 商品本体
 * @param images  图片路径列表（至少一张；多图表未就绪时只有主图）
 * @param stock   可售库存；{@code null} 表示商品表还没有库存字段
 * @param frozen  是否交易中（卖家正在与某位买家成交）
 * @param soldOut 是否售罄（库存为 0）
 * @author 周到
 */
public record ProductView(Product product,
                          List<String> images,
                          Integer stock,
                          boolean frozen,
                          boolean soldOut) {

    /** 能否提交购买意向 */
    public boolean tradable() {
        return !frozen && !soldOut;
    }

    /** 下单按钮旁的状态提示，正常在售时为空串 */
    public String statusTip() {
        if (frozen) {
            return "该商品正在交易中，暂时无法提交意向";
        }
        if (soldOut) {
            return "该商品已售罄";
        }
        return "";
    }

    /** 封面图：图集第一张 */
    public String cover() {
        return images.isEmpty() ? product.getImagePath() : images.get(0);
    }

    /** 是否多图（模板据此决定要不要渲染缩略图条） */
    public boolean hasGallery() {
        return images.size() > 1;
    }
}

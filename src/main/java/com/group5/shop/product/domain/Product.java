package com.group5.shop.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.util.MoneyUtils;

/**
 * 商品实体，对应表 {@code product}（T2-06，迭代 2 多商品化）。
 *
 * <p><b>价格字段特别说明</b>：数据库存的是 {@code price_cents}（整数「分」），
 * 不是 {@code double}。原因：{@code 9.90} 用 double 存会变成 9.9000000000000003，
 * 做「两位小数」「>0」这类校验时会出现边界误判。用分存整数彻底规避。</p>
 *
 * <p>页面里直接写 {@code th:text="${p.priceYuan}"} 即可拿到 {@code 9.90} 这样的字符串；
 * 提交表单时用 {@link MoneyUtils#yuanToCents(String)} 转回分。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属卖家 id，用于「我的商品」按人过滤（T2-10） */
    private Long sellerId;

    /** 所属分类 id，可为 null（未分类） */
    private Long categoryId;

    private String name;

    /** 商品描述，可空字符串 */
    private String description;

    /** 图片相对路径，如 {@code uploads/xxx.png}；未上传时为 {@code images/placeholder.png} */
    private String imagePath;

    /** 价格，单位「分」。CHECK > 0 */
    private Long priceCents;

    /** on_sale / delisted / frozen，见 {@link ProductStatus} */
    private String status;

    private Long publishedAt;

    private Long updatedAt;

    /** 下架（成交）时间，毫秒；在售时为 null */
    private Long tradedAt;

    /** success / failed / manual_delist，在售时为 null */
    private String tradeResult;

    // ------------------------------------------------------------------
    // 以下为「计算属性」，不对应数据库列，只供 Thymeleaf / JSON 展示使用
    // ------------------------------------------------------------------

    /** 价格（元，两位小数字符串），如 9.90 */
    public String getPriceYuan() {
        return MoneyUtils.centsToYuan(priceCents);
    }

    /** 状态中文名，如「在售」 */
    public String getStatusLabel() {
        return ProductStatus.labelOf(status);
    }

    /** 状态徽章 CSS 类，如 tag-green */
    public String getStatusCss() {
        return ProductStatus.cssOf(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(Long priceCents) {
        this.priceCents = priceCents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Long publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getTradedAt() {
        return tradedAt;
    }

    public void setTradedAt(Long tradedAt) {
        this.tradedAt = tradedAt;
    }

    public String getTradeResult() {
        return tradeResult;
    }

    public void setTradeResult(String tradeResult) {
        this.tradeResult = tradeResult;
    }
}

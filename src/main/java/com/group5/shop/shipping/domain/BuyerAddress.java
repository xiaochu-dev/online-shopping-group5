package com.group5.shop.shipping.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.util.TimeUtils;

/**
 * 收货地址（挑战需求 C.2.3，owner：胡成溯）。
 *
 * <p><b>为什么单独建表</b>：{@code buyer.default_place} 是迭代 1 的单值字段，
 * 「多个地址 + 选一个默认」无法用单值表达。本表承载多地址，
 * <b>默认地址变化时同步回写 {@code buyer.default_place}</b>，
 * 这样下单链路（订单域）不需要任何改动就能用上新地址。</p>
 *
 * <p><b>「至多一个默认」由库层保证</b>：{@code uq_addr_default} 是带
 * {@code WHERE is_default = 1} 的部分唯一索引，代码写错也不可能出现两个默认地址。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("buyer_address")
public class BuyerAddress {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long buyerId;

    /** 收货人姓名 */
    private String receiver;

    /** 收货人电话 */
    private String phone;

    /** 省市区 */
    private String region;

    /** 详细地址（街道门牌） */
    private String detail;

    /** 1 = 默认地址，0 = 普通地址 */
    private Integer isDefault;

    private Long createdAt;

    private Long updatedAt;

    /**
     * 是否为默认地址。
     *
     * @return true 表示默认
     */
    public boolean defaulted() {
        return isDefault != null && isDefault == 1;
    }

    /**
     * 完整地址（省市区 + 详细地址），用于订单快照与页面展示。
     *
     * @return 拼接后的地址文本
     */
    public String fullAddress() {
        return (region == null ? "" : region) + (detail == null ? "" : detail);
    }

    /**
     * 收货信息一行展示：收货人 电话。
     *
     * @return 展示文本
     */
    public String contactLine() {
        return (receiver == null ? "" : receiver) + "　" + (phone == null ? "" : phone);
    }

    /**
     * 最近修改时间，页面展示用。
     *
     * @return 形如 2026-11-20 15:30
     */
    public String updatedAtText() {
        return TimeUtils.formatDateTime(updatedAt);
    }

    // ==================================================================
    // getter / setter（与项目其它实体一致：手写，不引入 Lombok）
    // ==================================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}

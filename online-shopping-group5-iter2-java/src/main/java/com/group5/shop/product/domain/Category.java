package com.group5.shop.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.constant.CategoryStatus;

/**
 * 商品分类实体，对应表 {@code category}（T2-06，迭代 2 新增）。
 *
 * <p>用途：黄程宇的「发布页分类下拉框」与「分类维护页」直接使用本实体与
 * {@link com.group5.shop.product.domain.CategoryMapper}，无需自己建表。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    /** 同一父级下唯一；一级分类使用 NULL 父级。 */
    private String name;

    /** 下拉框排序号，越小越靠前 */
    private Integer sortNo;

    /** enabled 启用 / disabled 禁用，见 {@link CategoryStatus} */
    private String status;

    private Long createdAt;

    private Long updatedAt;

    /** 供页面直接使用：{@code ${c.statusLabel}} */
    public String getStatusLabel() {
        return CategoryStatus.labelOf(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

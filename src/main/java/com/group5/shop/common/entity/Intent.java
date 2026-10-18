package com.group5.shop.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.constant.IntentStatus;

/**
 * 购买意向实体，对应表 {@code intent}（T2-06）。
 *
 * <p>「同一号码重复提交互不干扰」的实现前提：本表<b>没有</b> phone 唯一约束，
 * 每次提交都是独立一行、独立 token_code、独立 id，因此同一买家提交 3 次就有 3 条排队记录。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("intent")
public class Intent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private String buyerName;

    /** 联系电话，不校验格式、不去重 */
    private String phone;

    /** 口令码：32 位随机十六进制，全局唯一，凭码撤销 */
    private String tokenCode;

    /** pending / trading / success / failed / cancelled，见 {@link IntentStatus} */
    private String status;

    /** 提交时间，毫秒；队列主排序键 */
    private Long createdAt;

    /** 供页面直接使用：{@code ${i.statusLabel}} */
    public String getStatusLabel() {
        return IntentStatus.labelOf(status);
    }

    /** 供页面判断「撤销」按钮是否可点 */
    public boolean isCancellable() {
        return IntentStatus.cancellable(status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTokenCode() {
        return tokenCode;
    }

    public void setTokenCode(String tokenCode) {
        this.tokenCode = tokenCode;
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
}

package com.group5.shop.portal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.util.TimeUtils;

/**
 * 购买意向实体，对应表 {@code intent}（T2-06 建立，T2-16 / T2-17 / T2-18 扩展）。
 *
 * <p>表 owner 是买家门户与交易模块（周到），因此本类的字段以 DDL 的
 * {@code schema/03-intent.sql} 为准；T2-01 模块化重构时随 intent 表整体迁到
 * {@code portal/domain/}，在此之前暂留 {@code common/entity/} 以免其他模块编译断链。</p>
 *
 * <h2>字段语义（挑战需求 A 之后）</h2>
 * <ul>
 *   <li>{@code buyerId}：下单人（NOT NULL）。「下单必须登录」之后身份就是账号，
 *       基线的匿名单落 0，永远查不到；</li>
 *   <li>{@code buyerName / phone / tradeAddress}：<b>下单时的快照</b>，
 *       客户之后改资料不影响历史单展示；</li>
 *   <li>{@code tokenCode}：基线遗留，新单不再生成（可为空），存量单仍可凭码撤销；</li>
 *   <li>{@code finishedAt / tradeResult / failReason}：A.2.2 的「历史下单」只展示
 *       <b>已有结果</b>的单，需要单据级的交易时间与结果——商品级的
 *       {@code product.traded_at} 装不下「同一商品卖多次」。</li>
 * </ul>
 *
 * @author 胡成溯（组长）建立；周到扩展 T2-16 ~ T2-18
 */
@TableName("intent")
public class Intent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号，如 {@code SO202611160001}；迭代 1~3 的存量单为 null */
    private String orderNo;

    /** 首件商品 id：兼容「一次意向 = 1 件」的旧视图，完整明细见 {@link OrderLine} */
    private Long productId;

    /** 下单人；0 表示迭代 2 基线遗留的匿名单 */
    private Long buyerId;

    /** 姓名快照（下单时取自买家资料） */
    private String buyerName;

    /** 电话快照，不校验格式、不去重 */
    private String phone;

    /** 交易地点快照（下单时取买家默认地点，允许当次改写） */
    private String tradeAddress;

    /** 口令码：基线遗留，新单为 null，全局唯一（部分索引） */
    private String tokenCode;

    /** created / confirmed / prepared / shipped / completed / cancelled，见 {@link IntentStatus} */
    private String status;

    /** 谁取消的：{@code buyer} / {@code seller}；未取消为 null。客户取消与商家取消的文案不同 */
    private String cancelledBy;

    /** 取消时间，毫秒；未取消为 null */
    private Long cancelledAt;

    /** 单据级交易结果：success / failed，未结束为 null */
    private String tradeResult;

    /** 失败原因，如「库存不足」「买家未到场」 */
    private String failReason;

    /** 下单时间，毫秒；队列主排序键 */
    private Long createdAt;

    /** 交易时间（置为 success / failed 的时刻），毫秒；未结束为 null */
    private Long finishedAt;

    // ------------------------------------------------------------------
    // 页面便捷方法
    // ------------------------------------------------------------------

    /** 供页面直接使用：{@code ${i.statusLabel}} */
    public String getStatusLabel() {
        return IntentStatus.labelOf(status);
    }

    /**
     * 状态对应的标签样式类（五态各一色，已取消为灰）。
     *
     * <p>放在实体里而不是模板里写嵌套三元：Thymeleaf 的三元一嵌套就没法读，
     * 而颜色规则以后要改（比如「已发货」换色），只该改一处。</p>
     */
    public String getStatusCss() {
        if (status == null) {
            return "tag-gray";
        }
        return switch (status) {
            case IntentStatus.CREATED -> "tag-blue";
            case IntentStatus.CONFIRMED, IntentStatus.PREPARED -> "tag-orange";
            case IntentStatus.SHIPPED, IntentStatus.COMPLETED, IntentStatus.SUCCESS -> "tag-green";
            case IntentStatus.CANCELLED, IntentStatus.FAILED -> "tag-gray";
            default -> "tag-gray";
        };
    }

    /** 供页面判断「撤销」按钮是否可点（迭代 4 起等同「客户能否取消」，B.1.2） */
    public boolean isCancellable() {
        return IntentStatus.canBuyerCancel(status);
    }

    /** 页面用：客户此刻能否取消（B.1.2，发货前） */
    public boolean isBuyerCancellable() {
        return IntentStatus.canBuyerCancel(status);
    }

    /** 页面用：商家此刻能否取消（B.1.3，完成前） */
    public boolean isSellerCancellable() {
        return IntentStatus.canSellerCancel(status);
    }

    /** 页面用：是否「进行中」的单（我的订单分区用） */
    public boolean isActiveOrder() {
        return IntentStatus.isActive(status);
    }

    /** 页面用：取消文案「您已取消 / 商家已取消」；未取消为空串 */
    public String getCancelText() {
        return IntentStatus.cancelSideLabel(cancelledBy);
    }

    /** 下单时间，格式化为 {@code 2026-10-21 16:30} */
    public String getCreatedAtText() {
        return TimeUtils.formatDateTime(createdAt);
    }

    /** 交易时间，未结束为空串 */
    public String getFinishedAtText() {
        return TimeUtils.formatDateTime(finishedAt);
    }

    /**
     * 交易结果的中文名；未结束返回空串。
     *
     * <p>迭代 4 起结果由 {@link #status} 表达（completed / cancelled），
     * 存量单仍是 {@code success / failed}，两种都要认。</p>
     */
    public String getTradeResultLabel() {
        if (IntentStatus.COMPLETED.equals(status) || IntentStatus.SUCCESS.equals(tradeResult)) {
            return "交易完成";
        }
        if (IntentStatus.CANCELLED.equals(status) || IntentStatus.FAILED.equals(tradeResult)) {
            return "已取消";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // getter / setter
    // ------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public Long getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Long cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(Long buyerId) {
        this.buyerId = buyerId;
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

    public String getTradeAddress() {
        return tradeAddress;
    }

    public void setTradeAddress(String tradeAddress) {
        this.tradeAddress = tradeAddress;
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

    public String getTradeResult() {
        return tradeResult;
    }

    public void setTradeResult(String tradeResult) {
        this.tradeResult = tradeResult;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }
}

package com.group5.shop.shipping.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.group5.shop.common.util.TimeUtils;

/**
 * 发货记录（挑战需求 C.2.1 / C.2.2，owner：胡成溯）。
 *
 * <p><b>一张订单至多一条</b>：{@code intent_id} 上是唯一约束，
 * 重复登记发货会被库层直接拒绝，不依赖「页面只点一次」。</p>
 *
 * <p><b>为什么不把承运商与单号塞进 {@code intent}</b>：订单表属订单域（周到）。
 * 配送是本模块职责，用 {@code intent_id} 外键挂上去，订单模块无需感知配送字段；
 * 将来若要增加「预计送达时间」也只改本表。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("shipment")
public class Shipment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应订单主键（唯一） */
    private Long intentId;

    /** 承运商代码，见 {@link Carrier} */
    private String carrier;

    /** 快递单号 */
    private String trackingNo;

    /** 发货时间（毫秒），物流轨迹以此推算 */
    private Long shippedAt;

    private Long createdAt;

    /**
     * 承运商中文名。
     *
     * @return 形如「顺丰速运」
     */
    public String carrierLabel() {
        return Carrier.label(carrier);
    }

    /**
     * 发货时间展示。
     *
     * @return 形如 2026-11-20 15:30
     */
    public String shippedAtText() {
        return TimeUtils.formatDateTime(shippedAt);
    }

    // ==================================================================
    // getter / setter
    // ==================================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIntentId() {
        return intentId;
    }

    public void setIntentId(Long intentId) {
        this.intentId = intentId;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public Long getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(Long shippedAt) {
        this.shippedAt = shippedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}

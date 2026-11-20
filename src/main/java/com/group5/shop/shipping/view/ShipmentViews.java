package com.group5.shop.shipping.view;

import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.shipping.service.ShipmentService.ShipmentView;
import com.group5.shop.shipping.service.TrackingService.TrackingEvent;

/**
 * 配送模块的页面视图对象（挑战需求 C.2，owner：胡成溯）。
 *
 * <p><b>为什么不直接把 record 丢给 Thymeleaf</b>：模板里的 {@code ${row.order.orderNo()}}
 * 依赖 SpEL 的方法调用，一旦某个字段只有 getter（如 {@code Shipment#getTrackingNo}），
 * 写法就要在 {@code trackingNo} 与 {@code trackingNo()} 之间来回切换，极易写错。
 * 这里统一成带 getter 的视图对象，模板只写属性名，不关心底层是 record 还是实体。</p>
 *
 * @author 胡成溯（组长）
 */
public final class ShipmentViews {

    private ShipmentViews() {
    }

    /** 物流列表的一行 */
    public static class Row {

        private final Long intentId;
        private final String orderNo;
        private final String itemsText;
        private final String carrierLabel;
        private final String trackingNo;
        private final String shippedAtText;
        private final String statusText;

        public Row(ShipmentView view) {
            this.intentId = view.order().intentId();
            this.orderNo = view.order().orderNo();
            this.itemsText = view.order().itemsText();
            this.carrierLabel = view.shipment().carrierLabel();
            this.trackingNo = view.shipment().getTrackingNo();
            this.shippedAtText = view.shipment().shippedAtText();
            this.statusText = view.trackingStatus();
        }

        public Long getIntentId() {
            return intentId;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getItemsText() {
            return itemsText;
        }

        public String getCarrierLabel() {
            return carrierLabel;
        }

        public String getTrackingNo() {
            return trackingNo;
        }

        public String getShippedAtText() {
            return shippedAtText;
        }

        public String getStatusText() {
            return statusText;
        }
    }

    /** 物流详情（订单信息 + 当前状态） */
    public static class Detail {

        private final String orderNo;
        private final String itemsText;
        private final String buyerName;
        private final String phone;
        private final String address;
        private final String carrierLabel;
        private final String trackingNo;
        private final String shippedAtText;
        private final String statusText;
        private final String latestTitle;

        public Detail(ShipmentView view) {
            this.orderNo = view.order().orderNo();
            this.itemsText = view.order().itemsText();
            this.buyerName = view.order().buyerName();
            this.phone = view.order().phone();
            this.address = view.order().address();
            this.carrierLabel = view.shipment().carrierLabel();
            this.trackingNo = view.shipment().getTrackingNo();
            this.shippedAtText = view.shipment().shippedAtText();
            this.statusText = view.trackingStatus();
            this.latestTitle = view.latest() == null ? "已揽收" : view.latest().title();
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getItemsText() {
            return itemsText;
        }

        public String getBuyerName() {
            return buyerName;
        }

        public String getPhone() {
            return phone;
        }

        public String getAddress() {
            return address;
        }

        public String getCarrierLabel() {
            return carrierLabel;
        }

        public String getTrackingNo() {
            return trackingNo;
        }

        public String getShippedAtText() {
            return shippedAtText;
        }

        public String getStatusText() {
            return statusText;
        }

        public String getLatestTitle() {
            return latestTitle;
        }
    }

    /** 卖家待发货列表的一行 */
    public static class Pending {

        private final Long intentId;
        private final String orderNo;
        private final String buyerName;
        private final String phone;
        private final String address;
        private final String itemsText;
        private final String createdAtText;

        public Pending(ShipmentView view) {
            this.intentId = view.order().intentId();
            this.orderNo = view.order().orderNo();
            this.buyerName = view.order().buyerName();
            this.phone = view.order().phone();
            this.address = view.order().address();
            this.itemsText = view.order().itemsText();
            this.createdAtText = TimeUtils.formatDateTime(view.order().createdAt());
        }

        public Long getIntentId() {
            return intentId;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getBuyerName() {
            return buyerName;
        }

        public String getPhone() {
            return phone;
        }

        public String getAddress() {
            return address;
        }

        public String getItemsText() {
            return itemsText;
        }

        public String getCreatedAtText() {
            return createdAtText;
        }
    }

    /** 物流轨迹一行 */
    public static class Event {

        private final String title;
        private final String detail;
        private final String timeText;
        private final boolean done;

        public Event(TrackingEvent event) {
            this.title = event.title();
            this.detail = event.detail();
            this.timeText = event.timeText();
            this.done = event.done();
        }

        public String getTitle() {
            return title;
        }

        public String getDetail() {
            return detail;
        }

        public String getTimeText() {
            return timeText;
        }

        public boolean isDone() {
            return done;
        }
    }
}

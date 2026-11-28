package com.group5.shop.aftersales;

import com.group5.shop.common.util.TimeUtils;

record AfterSalesRequest(long id, long orderId, String orderNo, String productName,
                         String buyerName, String phone, String title, String description,
                         long createdAt, Long negotiatingAt, Long resolvedAt,
                         Outcome outcome, String resolutionNote) {

    public String orderLabel() {
        return orderNo == null || orderNo.isBlank() ? "#" + orderId : orderNo;
    }

    public String stateLabel() {
        if (resolvedAt != null) {
            return "已处理";
        }
        return negotiatingAt == null ? "待处理" : "协商中";
    }

    public String createdAtText() {
        return TimeUtils.formatDateTime(createdAt);
    }

    public String negotiatingAtText() {
        return TimeUtils.formatDateTime(negotiatingAt);
    }

    public String resolvedAtText() {
        return TimeUtils.formatDateTime(resolvedAt);
    }

    enum Outcome {
        RETURN("退货"),
        REFUND("退款"),
        RETURN_REFUND("退货退款"),
        REPLACEMENT("换货"),
        REJECTED("未达成一致"),
        OTHER("其他");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}

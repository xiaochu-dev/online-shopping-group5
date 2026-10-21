package com.group5.shop.portal.contract;

/**
 * 下单所需的买家资料快照（T2-16）。
 *
 * <p>下单时把四项里要用的三项<b>抄进 intent</b>（姓名、电话、交易地点），
 * 而不是下单后再去查 buyer 表——客户之后改了电话，历史订单上显示的必须还是当时的号码。</p>
 *
 * @param id           买家主键
 * @param username     用户名（作为 buyer_name 快照）
 * @param phone        联系电话（必填）
 * @param defaultPlace 默认交易地点（选填，未填时为空串）
 * @author 周到
 */
public record BuyerProfile(Long id, String username, String phone, String defaultPlace) {

    /** 电话未填时用空串，避免下单时出现 null 拼接 */
    public String phoneOrEmpty() {
        return phone == null ? "" : phone;
    }

    /** 默认交易地点未填时用空串 */
    public String placeOrEmpty() {
        return defaultPlace == null ? "" : defaultPlace;
    }
}

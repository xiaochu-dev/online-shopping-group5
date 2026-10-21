package com.group5.shop.admin.port;

/**
 * 一条购买历史（客户详情页展示用）。
 *
 * <p>只暴露页面需要的字段，不把 portal 的 Intent 实体直接抛给 admin——
 * 否则 admin 就间接依赖了对方的领域模型，模块边界形同虚设。</p>
 *
 * @param productName 商品名称
 * @param createdAt   下单时间，毫秒时间戳
 * @param result      交易结果：success / failed（排队中与交易中的单不进历史）
 * @param finishedAt  交易完成时间，毫秒时间戳；未完成时可为 null
 * @param failReason  失败原因；成功时为 null
 * @author 胡成溯（组长）
 */
public record IntentHistoryItem(String productName,
                                Long createdAt,
                                String result,
                                Long finishedAt,
                                String failReason) {
}

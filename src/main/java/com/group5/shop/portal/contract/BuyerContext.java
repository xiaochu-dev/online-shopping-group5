package com.group5.shop.portal.contract;

/**
 * 买家登录态出口（对应任务表「五个跨模块出口」之一，提供方：胡成溯 T2-03）。
 *
 * <p><b>为什么 portal 不直接读 Session</b>：买家会话的键名、登录校验、买家表的形状
 * 都归账号与客户模块。门户只消费「当前是谁」和「他是谁」，会话细节不越界。</p>
 *
 * <p>本接口由账号模块提供实现；在其落地前，{@code ContractDefaults} 里有一份
 * 临时适配实现（读 {@code LOGIN_BUYER_ID} 会话属性 + buyer 表），正式 Bean 一出现即自动失效。</p>
 *
 * @author 周到
 */
public interface BuyerContext {

    /**
     * 当前登录买家主键。
     *
     * @return 未登录返回 {@code null}（<b>不要返回 0</b>，0 是存量匿名单的占位值）
     */
    Long currentId();

    /**
     * 买家资料快照来源。
     *
     * @param buyerId 买家主键
     * @return 买家资料；账号不存在返回 {@code null}
     */
    BuyerProfile profile(Long buyerId);
}

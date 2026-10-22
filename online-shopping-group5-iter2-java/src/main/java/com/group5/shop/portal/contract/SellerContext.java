package com.group5.shop.portal.contract;

/**
 * 卖家登录态出口（任务表「五个跨模块出口」之一，提供方：胡成溯 T2-03）。
 *
 * <p><b>为什么 T2-18 的意向管理页在 portal 包下却要卖家的身份</b>：
 * 意向状态机与 intent 表归门户模块，所以页面代码放 {@code portal/}；
 * 但操作它的人是卖家，鉴权来自账号模块。两者拆开后，门户不会为了一个后台页
 * 去 import 卖家实体，账号模块也不用知道意向表长什么样。</p>
 *
 * @author 周到
 */
public interface SellerContext {

    /**
     * 当前登录卖家主键。
     *
     * @return 未登录返回 {@code null}
     */
    Long currentId();

    /**
     * 当前登录卖家用户名，用于页面右上角显示。
     *
     * @return 未登录返回 {@code null}
     */
    String currentName();
}

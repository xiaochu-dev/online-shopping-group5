package com.group5.shop.common.constant;

/**
 * Session 属性名常量。
 *
 * <p>登录态只存用户名，不存整个 Seller 对象：<br>
 * ① 用户改密后重新查库能拿到最新数据；<br>
 * ② 避免 Session 里放可变对象带来的「改了 Session 却没落库」的隐性 bug。</p>
 *
 * @author 胡成溯（组长）
 */
public final class SessionKeys {

    /** 当前登录卖家用户名（String） */
    public static final String LOGIN_SELLER = "LOGIN_SELLER";

    /** 当前登录卖家主键 id（Long），便于商品模块按 seller_id 过滤 */
    public static final String LOGIN_SELLER_ID = "LOGIN_SELLER_ID";

    private SessionKeys() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}

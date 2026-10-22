package com.group5.shop.common.constant;

/**
 * 意向状态常量（T2-06）。
 *
 * <p>迭代 2 只用到 {@link #PENDING}（提交即入队）与 {@link #CANCELLED}（凭口令码撤销）；
 * 其余三个状态由迭代 3 的「进入交易 / 递补 / 标记结果」写入，schema.sql 的 CHECK 约束已提前放开。</p>
 *
 * @author 胡成溯（组长）
 */
public final class IntentStatus {

    /** 排队中：提交成功后的初始状态，可撤销 */
    public static final String PENDING = "pending";

    /** 交易中：迭代 3 使用 */
    public static final String TRADING = "trading";

    /** 交易成功：迭代 3 使用 */
    public static final String SUCCESS = "success";

    /** 交易失败：迭代 3 使用 */
    public static final String FAILED = "failed";

    /** 已撤销：买家凭口令码主动撤销 */
    public static final String CANCELLED = "cancelled";

    private IntentStatus() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /** 状态值 → 页面展示的中文名 */
    public static String labelOf(String status) {
        if (PENDING.equals(status)) {
            return "排队中";
        }
        if (TRADING.equals(status)) {
            return "交易中";
        }
        if (SUCCESS.equals(status)) {
            return "交易成功";
        }
        if (FAILED.equals(status)) {
            return "交易失败";
        }
        if (CANCELLED.equals(status)) {
            return "已撤销";
        }
        return "未知状态";
    }

    /** 是否可撤销（只有排队中的意向能撤销，交易中/终态都不行） */
    public static boolean cancellable(String status) {
        return PENDING.equals(status);
    }
}

package com.group5.shop.common.constant;

/**
 * 分类状态常量（T2-06）。
 *
 * <p>禁用（disabled）的分类不再出现在发布页下拉框，但已归入该分类的历史商品不受影响。</p>
 *
 * @author 胡成溯（组长）
 */
public final class CategoryStatus {

    /** 启用：可在发布页选择 */
    public static final String ENABLED = "enabled";

    /** 禁用：下拉框不再显示，历史商品保持原样 */
    public static final String DISABLED = "disabled";

    private CategoryStatus() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    public static String labelOf(String status) {
        if (ENABLED.equals(status)) {
            return "启用";
        }
        if (DISABLED.equals(status)) {
            return "禁用";
        }
        return "未知状态";
    }
}

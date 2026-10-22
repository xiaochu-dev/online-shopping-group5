package com.group5.shop.common.util;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 金额换算工具（T2-01，商品模块强依赖）。
 *
 * <p>全项目约定：<b>数据库与 Java 内部一律用「分」（long），只在页面展示与表单输入时用「元」。</b>
 * 这样永远不会出现 {@code 9.90} 存成 {@code 9.9000000000000004} 的浮点误差。</p>
 *
 * <pre>
 * String 输入 "9.9"   → long 990
 * String 输入 "9.90"  → long 990
 * String 输入 "9.999" → 抛 BusinessException("价格最多两位小数")
 * String 输入 "0"     → 抛 BusinessException("价格必须大于 0")
 * String 输入 "abc"   → 抛 BusinessException("价格格式非法")
 * </pre>
 *
 * @author 胡成溯（组长）
 */
public final class MoneyUtils {

    /** 合法价格：1~9 位整数部分，可选 1~2 位小数 */
    private static final Pattern PRICE_PATTERN = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private static final BigDecimal MIN_PRICE = BigDecimal.ZERO;

    private MoneyUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 「元」字符串 → 「分」整数。
     *
     * @param yuan 用户表单输入的价格，如 "9.90"
     * @return 价格（分），一定 &gt; 0
     * @throws BusinessException 为空、格式非法、小数超两位、不大于 0 时抛出
     */
    public static long yuanToCents(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "价格不能为空");
        }
        String text = yuan.trim();
        if (!PRICE_PATTERN.matcher(text).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "价格格式非法，最多两位小数");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.compareTo(MIN_PRICE) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "价格必须大于 0");
        }
        return value.movePointRight(2).longValueExact();
    }

    /**
     * 「分」整数 → 「元」字符串（保留两位小数）。
     *
     * @param cents 价格（分），允许为 null
     * @return 如 "9.90"；null 返回 "0.00"
     */
    public static String centsToYuan(Long cents) {
        if (cents == null) {
            return "0.00";
        }
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}

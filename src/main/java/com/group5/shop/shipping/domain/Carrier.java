package com.group5.shop.shipping.domain;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;

import java.util.List;

/**
 * 承运商（挑战需求 C.2.2：线下配送支持顺丰等优质快递）。
 *
 * <p><b>为什么用常量表而不是枚举</b>：承运商会随业务增加（顺丰、京东、中通、圆通……），
 * 枚举一旦被存进数据库，改名就要改历史数据；这里保存的是「代码」（如 {@code SF}），
 * 展示名只在内存里映射，库里永远是短代码。</p>
 *
 * <p>新增承运商只需在 {@link #ALL} 里加一项，页面下拉框自动多一个选项。</p>
 *
 * @author 胡成溯（组长）
 */
public final class Carrier {

    /** 顺丰速运 */
    public static final String SF = "SF";
    /** 京东物流 */
    public static final String JD = "JD";
    /** 中通快递 */
    public static final String ZTO = "ZTO";
    /** 圆通速递 */
    public static final String YTO = "YTO";

    /** 页面上可选的承运商，顺序即展示顺序 */
    public static final List<String> ALL = List.of(SF, JD, ZTO, YTO);

    private Carrier() {
    }

    /**
     * 是否为受支持的承运商。
     *
     * @param code 承运商代码
     * @return true 表示可选
     */
    public static boolean supported(String code) {
        return code != null && ALL.contains(code);
    }

    /**
     * 承运商展示名，未知代码原样返回，避免历史数据渲染成空白。
     *
     * @param code 承运商代码
     * @return 中文展示名
     */
    public static String label(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case SF -> "顺丰速运";
            case JD -> "京东物流";
            case ZTO -> "中通快递";
            case YTO -> "圆通速递";
            default -> code;
        };
    }

    /**
     * 校验承运商，不合法直接抛业务异常。
     *
     * @param code 承运商代码
     * @return 规范化后的代码
     */
    public static String require(String code) {
        if (!supported(code)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择支持的承运商（顺丰 / 京东 / 中通 / 圆通）");
        }
        return code;
    }
}

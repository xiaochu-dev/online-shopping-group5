package com.group5.shop.common.exception;

/**
 * 业务异常（T2-01）。
 *
 * <p>作用：让 Service 层可以「只管抛、不管怎么展示」。
 * 抛出后由 {@link GlobalExceptionHandler} 统一处理：
 * <ul>
 *   <li>{@code /api/**} 请求 → 返回 {@code ApiResponse}，HTTP 状态取 {@link ErrorCode#getHttpStatus()}</li>
 *   <li>页面请求 → 跳转到错误页，页面显示 {@code message}</li>
 * </ul>
 *
 * <p>示例：<br>
 * {@code throw new BusinessException(ErrorCode.LOGIN_FAILED);}<br>
 * {@code throw new BusinessException(ErrorCode.PARAM_INVALID, "商品名称不能为空");}</p>
 *
 * <p>继承 {@link RuntimeException} 而非受检异常，是因为业务校验失败属于「正常流程分支」，
 * 用受检异常会迫使每个 Service 方法都声明 throws，代码噪音大。</p>
 *
 * @author 胡成溯（组长）
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** 使用错误码的默认提示语 */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 使用自定义提示语（覆盖错误码默认提示，便于给出字段级说明） */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    public int getHttpStatus() {
        return errorCode.getHttpStatus();
    }
}

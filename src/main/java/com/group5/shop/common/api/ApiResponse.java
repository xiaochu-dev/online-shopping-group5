package com.group5.shop.common.api;

import java.io.Serializable;

/**
 * 统一返回体（T2-01）。
 *
 * <p>约定：所有 {@code /api/**} 接口一律返回本对象，前端只需判断 {@code code == 0}。
 * 页面路由（Thymeleaf 渲染）不走本对象，直接返回视图名。</p>
 *
 * <pre>
 * 成功：{ "code": 0, "message": "ok", "data": {...}, "timestamp": 1760000000000 }
 * 失败：{ "code": 40001, "message": "商品名称不能为空", "data": null, "timestamp": 1760000000000 }
 * </pre>
 *
 * @param <T> 业务数据类型
 * @author 胡成溯（组长）
 */
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，0 表示成功，非 0 见 {@link com.group5.shop.common.exception.ErrorCode} */
    private int code;

    /** 提示信息，可直接展示给用户 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /** 服务器时间戳（毫秒），便于排查前端缓存问题 */
    private long timestamp;

    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /** 成功并携带数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /** 成功但无返回数据。 */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /** 失败，携带业务错误码与提示语。 */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public boolean isSuccess() {
        return this.code == 0;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}

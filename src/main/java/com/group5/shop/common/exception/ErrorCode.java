package com.group5.shop.common.exception;

/**
 * 业务错误码（T2-01）。
 *
 * <p>编码规则：5 位数字，前 3 位表示错误大类，后 2 位是该类下的序号：
 * <ul>
 *   <li>400xx —— 参数错误（HTTP 400）</li>
 *   <li>401xx —— 账号 / 鉴权相关（多数 HTTP 401，但「原密码错误」「新密码过短」属于表单校验失败，
 *       实际 HTTP 状态是 400，以本枚举 {@link #getHttpStatus()} 为准）</li>
 *   <li>404xx —— 资源不存在（HTTP 404）</li>
 *   <li>500xx —— 服务端内部错误（HTTP 500）</li>
 * </ul>
 * {@code 0} 单独表示成功。</p>
 *
 * <p><b>注意</b>：HTTP 状态码不要用「业务码 / 100」去反推，必须读 {@link #getHttpStatus()}。</p>
 *
 * <p>黄程宇、周到两位同学如需新增业务错误码，请在各自模块的 41xxx / 42xxx 段内追加，
 * 不要修改已有值，避免前端判断逻辑失效。</p>
 *
 * @author 胡成溯（组长）
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "ok", 200),

    /** 通用参数校验不通过 */
    PARAM_INVALID(40001, "参数不合法", 400),

    /** 请求的资源不存在 */
    NOT_FOUND(40401, "资源不存在", 404),

    /** 账号或密码错误 */
    LOGIN_FAILED(40101, "用户名或密码错误", 401),

    /** 未登录 / 会话已过期 */
    UNAUTHORIZED(40102, "登录已过期，请重新登录", 401),

    /** 原密码不正确 */
    OLD_PASSWORD_MISMATCH(40103, "原密码错误", 400),

    /** 新密码长度不足 8 位 */
    PASSWORD_TOO_SHORT(40104, "新密码长度不得少于 8 位", 400),

    /** 账号不存在 */
    ACCOUNT_NOT_FOUND(40105, "账号不存在", 404),

    /** 服务端内部错误 */
    INTERNAL_ERROR(50001, "服务器开小差了，请稍后再试", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}

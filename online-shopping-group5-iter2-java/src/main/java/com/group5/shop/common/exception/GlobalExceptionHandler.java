package com.group5.shop.common.exception;

import com.group5.shop.common.api.ApiResponse;
import com.group5.shop.common.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（T2-01）。
 *
 * <p>核心思路：<b>同一套异常，两种出口</b>。</p>
 * <table border="1">
 *   <caption>出口选择规则</caption>
 *   <tr><th>请求类型</th><th>判定方式</th><th>返回什么</th></tr>
 *   <tr><td>接口请求</td><td>URI 以 {@code /api/} 开头，或 {@code Accept: application/json}，
 *       或 {@code X-Requested-With: XMLHttpRequest}</td><td>{@link ApiResponse} JSON</td></tr>
 *   <tr><td>页面请求</td><td>其余情况</td><td>{@code templates/error/error.html} 错误页</td></tr>
 * </table>
 *
 * <p>为什么不用两个 Advice（一个 RestControllerAdvice、一个 ControllerAdvice）？
 * 因为 {@code @RestController} 本身带 {@code @Controller} 元注解，两个 Advice 会同时命中同一个
 * Controller，谁生效取决于 {@code @Order}，排查成本高。这里用「一个入口 + 按 URI 分流」，
 * 规则显式、可预期。</p>
 *
 * @author 胡成溯（组长）
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：参数不合法、登录失败、密码错误等「预期内」的失败 */
    @ExceptionHandler(BusinessException.class)
    public Object handleBusinessException(BusinessException ex,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        log.warn("业务异常 [{}] {} {}", ex.getCode(), request.getMethod(), request.getRequestURI(), ex);
        response.setStatus(ex.getHttpStatus());
        return render(ex.getCode(), ex.getMessage(), ex.getHttpStatus(), request);
    }

    /**
     * 表单 / JSON 参数校验失败。
     *
     * <p>{@code MethodArgumentNotValidException} 在 Spring 6 中继承自 {@link BindException}，
     * 因此这里只需捕获父类即可同时覆盖两种场景。</p>
     */
    @ExceptionHandler(BindException.class)
    public Object handleBindException(BindException ex,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        log.warn("参数校验失败 {} {}：{}", request.getMethod(), request.getRequestURI(), message);
        response.setStatus(ErrorCode.PARAM_INVALID.getHttpStatus());
        return render(ErrorCode.PARAM_INVALID.getCode(), message,
                ErrorCode.PARAM_INVALID.getHttpStatus(), request);
    }

    /** 访问了不存在的静态资源 / 路径，返回 404 而不是 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResourceFound(NoResourceFoundException ex,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        log.warn("资源不存在 {}", request.getRequestURI());
        response.setStatus(ErrorCode.NOT_FOUND.getHttpStatus());
        return render(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage(),
                ErrorCode.NOT_FOUND.getHttpStatus(), request);
    }

    /** 兜底：任何未预料到的异常。日志打全栈便于排查，页面只给通用提示，不泄露堆栈 */
    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        log.error("未处理异常 {} {}", request.getMethod(), request.getRequestURI(), ex);
        response.setStatus(ErrorCode.INTERNAL_ERROR.getHttpStatus());
        return render(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage(),
                ErrorCode.INTERNAL_ERROR.getHttpStatus(), request);
    }

    /**
     * 按请求类型渲染：接口返回 JSON，页面返回视图。
     *
     * @param httpStatus 精确的 HTTP 状态码（由 ErrorCode 决定，不能从业务码反推——
     *                   例如「原密码错误 40103」的 HTTP 状态是 400，而 40103/100 会算成 401）
     * @return {@link ResponseEntity} 或 {@link ModelAndView}，由 Spring 按实际类型选择处理器
     */
    private Object render(int code, String message, int httpStatus, HttpServletRequest request) {
        if (RequestUtils.isApiRequest(request)) {
            return ResponseEntity.status(httpStatus)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.fail(code, message));
        }
        ModelAndView mav = new ModelAndView("error/error");
        mav.addObject("code", code);
        mav.addObject("message", message);
        mav.addObject("path", request.getRequestURI());
        return mav;
    }

}

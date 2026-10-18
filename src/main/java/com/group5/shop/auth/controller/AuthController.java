package com.group5.shop.auth.controller;

import com.group5.shop.auth.service.AuthService;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.common.entity.Seller;
import com.group5.shop.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 账号控制器（T2-05）：登录、退出、修改密码。
 *
 * <p>路由一览：</p>
 * <table border="1">
 *   <caption>账号模块路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>权限</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/login</td><td>公开</td><td>登录页；已登录则直接跳 /admin</td></tr>
 *   <tr><td>POST</td><td>/login</td><td>公开</td><td>提交账号密码，成功写 Session 并 302</td></tr>
 *   <tr><td>GET</td><td>/logout</td><td>公开</td><td>销毁 Session，回登录页</td></tr>
 *   <tr><td>POST</td><td>/admin/password</td><td>需登录</td><td>修改密码，303 回后台并带 msg / error</td></tr>
 * </table>
 *
 * <p><b>提示语传递约定（全项目统一）</b>：表单提交成功后 303 重定向到
 * {@code ?msg=xxx}，失败重定向到 {@code ?error=xxx}，中文经 UTF-8 编码。
 * 用 303 而不是直接渲染，是为了避免用户按 F5 重复提交表单（Post/Redirect/Get 模式）。
 * 黄程宇、周到的模块请沿用同一约定。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class AuthController {

    /** 登录后免重复登录 7 天 */
    private static final int SESSION_TTL_SECONDS = 7 * 24 * 3600;

    /** 登录成功后的默认落地页 */
    private static final String DEFAULT_REDIRECT = "/admin";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /** 登录页。已登录用户直接进后台，避免重复登录 */
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "redirect", required = false) String redirect,
                            HttpServletRequest request,
                            Model model) {
        if (currentUsername(request) != null) {
            return "redirect:" + DEFAULT_REDIRECT;
        }
        model.addAttribute("redirect", safeRedirect(redirect));
        return "auth/login";
    }

    /**
     * 提交登录。
     *
     * <p>失败时<b>不回 401</b>，而是重新渲染登录页并显示错误——因为这是页面表单，
     * 返回 401 会让浏览器显示一个空白错误页，体验很差。接口请求（/api/**）才用 401 + JSON。</p>
     */
    @PostMapping("/login")
    public String doLogin(@RequestParam(value = "username", required = false) String username,
                          @RequestParam(value = "password", required = false) String password,
                          @RequestParam(value = "redirect", required = false) String redirect,
                          HttpServletRequest request,
                          Model model) {
        String target = safeRedirect(redirect);
        try {
            Seller seller = authService.login(username, password);

            // 防「会话固定攻击」：登录前后换一个全新的 SessionId，
            // 这样攻击者事先塞给你的 SessionId 在登录后就失效了
            HttpSession session = request.getSession(true);
            request.changeSessionId();
            session.setAttribute(SessionKeys.LOGIN_SELLER, seller.getUsername());
            session.setAttribute(SessionKeys.LOGIN_SELLER_ID, seller.getId());
            session.setMaxInactiveInterval(SESSION_TTL_SECONDS);

            return "redirect:" + target;
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("username", username);
            model.addAttribute("redirect", target);
            return "auth/login";
        }
    }

    /** 退出登录（迭代 2 补充的便利功能，见 README「与需求的差异说明」） */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    // ------------------------------------------------------------------
    // 修改密码
    // ------------------------------------------------------------------

    /**
     * 修改密码（FR-01-4 / FR-01-5）。
     *
     * <p>注意 {@code username} 取自 Session 而不是表单——
     * 如果从表单取，攻击者只要改一下隐藏域就能改别人的密码（越权）。</p>
     */
    @PostMapping("/admin/password")
    public String changePassword(@RequestParam(value = "oldPassword", required = false) String oldPassword,
                                 @RequestParam(value = "newPassword", required = false) String newPassword,
                                 @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
                                 HttpServletRequest request) {
        String username = currentUsername(request);
        if (username == null) {
            // 理论上拦截器已兜住，这里再判一次，防止将来拦截器配置被改坏
            return "redirect:/login";
        }
        if (newPassword != null && !newPassword.equals(confirmPassword)) {
            return "redirect:" + DEFAULT_REDIRECT + "?error=" + encode("两次输入的新密码不一致");
        }
        try {
            authService.changePassword(username, oldPassword, newPassword);
            return "redirect:" + DEFAULT_REDIRECT + "?msg=" + encode("密码修改成功，下次登录请使用新密码");
        } catch (BusinessException e) {
            return "redirect:" + DEFAULT_REDIRECT + "?error=" + encode(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 取当前登录用户名，未登录返回 null */
    private String currentUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SessionKeys.LOGIN_SELLER);
        return value == null ? null : value.toString();
    }

    /**
     * 只允许跳回本系统内部路径，防止「开放重定向」漏洞。
     *
     * <p>如果直接把用户传来的 redirect 拼进 Location 头，
     * 攻击者可构造 {@code /login?redirect=https://钓鱼站}，
     * 用户登录成功后就被带去钓鱼页，且看起来是从正规站点跳过去的。</p>
     */
    private String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return DEFAULT_REDIRECT;
        }
        String target = redirect.trim();
        // 必须是站内绝对路径：以单个 / 开头（排除 //evil.com 这种协议相对地址）
        if (!target.startsWith("/") || target.startsWith("//")) {
            return DEFAULT_REDIRECT;
        }
        // 不要绕回登录页自身，否则登录成功后原地打转
        if (target.startsWith("/login")) {
            return DEFAULT_REDIRECT;
        }
        return target;
    }

    /** 中文提示语做 URL 编码，避免重定向地址里出现非法字符 */
    private String encode(String message) {
        return UriUtils.encode(message == null ? "" : message, StandardCharsets.UTF_8);
    }
}

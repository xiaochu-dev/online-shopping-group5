package com.group5.shop.buyer.controller;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.service.BuyerAuthService;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 买家账号控制器（T2-02 / T2-03，挑战需求 A.2.1）。
 *
 * <table border="1">
 *   <caption>买家账号路由</caption>
 *   <tr><th>方法</th><th>路径</th><th>权限</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/buyer/register</td><td>公开</td><td>注册页</td></tr>
 *   <tr><td>POST</td><td>/buyer/register</td><td>公开</td><td>提交注册，303 去登录页</td></tr>
 *   <tr><td>GET</td><td>/buyer/login</td><td>公开</td><td>买家登录页（已登录则跳回原页）</td></tr>
 *   <tr><td>POST</td><td>/buyer/login</td><td>公开</td><td>校验通过后写 BUYER_SESSION cookie</td></tr>
 *   <tr><td>GET</td><td>/buyer/logout</td><td>公开</td><td>清除 cookie</td></tr>
 *   <tr><td>GET</td><td>/buyer/me</td><td>需登录</td><td>买家中心（账号信息）</td></tr>
 * </table>
 *
 * <p>与卖家账号的隔离：卖家登录态在 {@code SHOP_SESSION}（HttpSession），
 * 买家登录态在 {@code BUYER_SESSION}（签名 cookie），两者互不覆盖，
 * 同一浏览器可同时登录卖家后台与买家门户。</p>
 *
 * @author 胡成溯（组长）
 */
@Controller
public class BuyerAuthController {

    /** 登录后的默认落地页（买家中心） */
    private static final String DEFAULT_REDIRECT = "/buyer/me";

    private final BuyerAuthService buyerAuthService;
    private final BuyerSessionCookie sessionCookie;
    private final BuyerContext buyerContext;

    public BuyerAuthController(BuyerAuthService buyerAuthService,
                               BuyerSessionCookie sessionCookie,
                               BuyerContext buyerContext) {
        this.buyerAuthService = buyerAuthService;
        this.sessionCookie = sessionCookie;
        this.buyerContext = buyerContext;
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 注册页 */
    @GetMapping("/buyer/register")
    public String registerPage(Model model) {
        return "buyer/register";
    }

    /** 提交注册：成功后去登录页，带上 msg；失败回填表单 */
    @PostMapping("/buyer/register")
    public String doRegister(@RequestParam(value = "username", required = false) String username,
                             @RequestParam(value = "password", required = false) String password,
                             @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
                             @RequestParam(value = "phone", required = false) String phone,
                             @RequestParam(value = "defaultPlace", required = false) String defaultPlace,
                             Model model) {
        if (password != null && !password.equals(confirmPassword)) {
            return backToRegister(model, username, phone, defaultPlace, "两次输入的密码不一致");
        }
        try {
            buyerAuthService.register(username, password, phone, defaultPlace);
            return "redirect:/buyer/login?msg=" + encode("注册成功，请使用新账号登录");
        } catch (BusinessException e) {
            return backToRegister(model, username, phone, defaultPlace, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 登录 / 退出
    // ------------------------------------------------------------------

    /** 买家登录页 */
    @GetMapping("/buyer/login")
    public String loginPage(@RequestParam(value = "redirect", required = false) String redirect,
                            @RequestParam(value = "msg", required = false) String msg,
                            Model model) {
        if (buyerContext.current() != null) {
            return "redirect:" + safeRedirect(redirect);
        }
        model.addAttribute("redirect", safeRedirect(redirect));
        model.addAttribute("msg", msg);
        return "buyer/login";
    }

    /**
     * 提交登录。
     *
     * <p>登录成功后把签名 cookie 写进响应头，而不是往 HttpSession 塞属性——
     * 只有这样才能与卖家的 {@code SHOP_SESSION} 共存。</p>
     */
    @PostMapping("/buyer/login")
    public String doLogin(@RequestParam(value = "username", required = false) String username,
                          @RequestParam(value = "password", required = false) String password,
                          @RequestParam(value = "redirect", required = false) String redirect,
                          HttpServletResponse response,
                          Model model) {
        String target = safeRedirect(redirect);
        try {
            Buyer buyer = buyerAuthService.login(username, password);
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.issue(buyer.getId()).toString());
            return "redirect:" + target;
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("username", username);
            model.addAttribute("redirect", target);
            return "buyer/login";
        }
    }

    /** 退出登录：清 cookie 后去登录页 */
    @GetMapping("/buyer/logout")
    public String logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie.clear().toString());
        return "redirect:/buyer/login";
    }

    // ------------------------------------------------------------------
    // 买家中心
    // ------------------------------------------------------------------

    /** 买家中心：登录后展示自己的账号信息 */
    @GetMapping("/buyer/me")
    public String me(Model model) {
        Buyer buyer = buyerContext.current();
        if (buyer == null) {
            // 拦截器已兜住，这里再判一次，防止拦截器配置被改坏后越权展示
            return "redirect:/buyer/login?redirect=%2Fbuyer%2Fme";
        }
        model.addAttribute("buyer", buyer);
        return "buyer/me";
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 注册失败时回填已填字段，避免用户重填 */
    private String backToRegister(Model model, String username, String phone,
                                  String defaultPlace, String error) {
        model.addAttribute("error", error);
        model.addAttribute("username", username);
        model.addAttribute("phone", phone);
        model.addAttribute("defaultPlace", defaultPlace);
        return "buyer/register";
    }

    /**
     * 只允许站内跳转，防开放重定向（与卖家登录同一策略）。
     *
     * @param redirect 用户传来的目标地址
     * @return 安全的目标地址
     */
    private String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return DEFAULT_REDIRECT;
        }
        String target = redirect.trim();
        if (!target.startsWith("/") || target.startsWith("//")) {
            return DEFAULT_REDIRECT;
        }
        if (target.startsWith("/buyer/login") || target.startsWith("/buyer/register")) {
            return DEFAULT_REDIRECT;
        }
        return target;
    }

    /** 中文提示语 URL 编码 */
    private String encode(String message) {
        return UriUtils.encode(message == null ? "" : message, StandardCharsets.UTF_8);
    }
}

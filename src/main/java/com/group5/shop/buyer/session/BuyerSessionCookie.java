package com.group5.shop.buyer.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 买家登录态 Cookie（T2-03）。
 *
 * <p><b>为什么不直接用 HttpSession</b>：全项目只有一个 {@code SHOP_SESSION} cookie，
 * 买家和卖家共用同一个会话容器时，一方登录会覆盖另一方的登录态（买菜时把卖家挤下线）。
 * 澄清结论要求「卖家后台与买家门户两套独立 cookie，可同浏览器共存」，
 * 因此买家登录态单独走 {@code BUYER_SESSION}。</p>
 *
 * <p><b>设计</b>：无状态签名 cookie，值为 {@code buyerId:过期毫秒:HMAC-SHA256}。
 * <ul>
 *   <li>不建会话表：课程演示数据量小，避免多一张表与清理任务；</li>
 *   <li>签名用 HMAC 而非明文 {@code buyerId}：否则用户只要把 cookie 改成 {@code 1}
 *       就能冒充别的买家（水平越权）；</li>
 *   <li>校验用 {@link MessageDigest#isEqual} 常量时间比较，防时序侧信道；</li>
 *   <li>密钥每次启动随机生成：进程重启后旧 cookie 自动失效（与 HttpSession 行为一致）。</li>
 * </ul>
 * </p>
 *
 * <p><b>局限（需向评审说明）</b>：无状态方案无法在服务端主动吊销单个 cookie，
 * 退出登录只能清掉浏览器侧那份。若后续要强制下线，需改为「会话表 + 服务端删除」。</p>
 *
 * @author 胡成溯（组长）
 */
@Component
public class BuyerSessionCookie {

    /** 买家登录态 cookie 名，与卖家的 SHOP_SESSION 互不干扰 */
    public static final String COOKIE_NAME = "BUYER_SESSION";

    /** 登录态有效期 7 天，与卖家一致 */
    private static final int TTL_SECONDS = 7 * 24 * 3600;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 每次启动随机生成的签名密钥（32 字节） */
    private final byte[] secret = new byte[32];

    public BuyerSessionCookie() {
        new SecureRandom().nextBytes(secret);
    }

    /**
     * 签发登录态 cookie。
     *
     * @param buyerId 买家主键
     * @return 可直接写进响应头的 cookie
     */
    public ResponseCookie issue(Long buyerId) {
        long expireAt = System.currentTimeMillis() + TTL_SECONDS * 1000L;
        String payload = buyerId + ":" + expireAt;
        String value = payload + ":" + sign(payload);
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)          // 禁止 JS 读取，降低 XSS 窃取风险
                .path("/")
                .maxAge(Duration.ofSeconds(TTL_SECONDS))
                .sameSite("Lax")
                .build();
    }

    /**
     * 清除 cookie（退出登录）。
     *
     * @return maxAge = 0 的 cookie
     */
    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    /**
     * 解析并校验 cookie。
     *
     * @param request 当前请求
     * @return 校验通过的买家 id；未登录、被篡改、已过期均返回 {@link Optional#empty()}
     */
    public Optional<Long> resolve(HttpServletRequest request) {
        String raw = readCookie(request);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String payload = parts[0] + ":" + parts[1];
        if (!MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            // 签名不符 = 有人改过 cookie
            return Optional.empty();
        }
        long expireAt;
        long buyerId;
        try {
            buyerId = Long.parseLong(parts[0]);
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (expireAt < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(buyerId);
    }

    /** 从请求里读 cookie 值；不存在返回 null */
    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** HMAC-SHA256 签名，输出十六进制 */
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("买家登录态签名失败", e);
        }
    }
}

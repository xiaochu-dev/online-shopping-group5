package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 权限回归测试：验证「未登录被拦截」「两套登录态隔离」「水平越权不泄露」。
 *
 * <p>与 {@code BuyerAuthTest} 同样的口径：走真实拦截器，因为只有经过
 * {@code LoginInterceptor}（拦 /admin/**）与 {@code BuyerLoginInterceptor}（拦 /buyer/me、/my/**）
 * 才能证明这些权限边界真实生效。</p>
 *
 * <p>两套登录态隔离是本类重点：卖家登录态在 {@code SHOP_SESSION}（HttpSession），
 * 买家登录态在 {@code BUYER_SESSION}（签名 cookie）。本类逐一验证：
 * <ul>
 *   <li>买家 cookie 访问卖家后台 → 被卖家拦截器拦下；</li>
 *   <li>卖家 session 访问买家中心 → 被买家拦截器拦下；</li>
 *   <li>买家 A 篡改 cookie 冒充买家 B → 签名校验失败，被当未登录处理，不泄露 B 的资料。</li>
 * </ul></p>
 *
 * <p>本类使用独立临时数据库（{@code target/test-authorization-*.db}），不影响其他测试类。
 * 用例按 {@code @Order} 执行：先建买家 A/B 两个账号，再登录拿两套登录态，最后测越权。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorizationTest {

    /** 本测试类专用数据库 */
    private static final Path TEST_DB =
            Path.of("target", "test-authorization-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    /** 两个买家账号，带时间戳前缀互不相同，且均不含对方子串，便于断言「不泄露」 */
    private static final String BUYER_A = "authzA" + System.nanoTime() % 100000;
    private static final String BUYER_B = "authzB" + System.nanoTime() % 100000;

    private static final String PASSWORD = "buyer12345";

    /** 买家 A 登录后拿到的 cookie 值 */
    private static String buyerACookieValue;

    /** 卖家登录会话 */
    private static MockHttpSession sellerSession;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BuyerMapper buyerMapper;

    // ==================================================================
    // 一、先建账号（供后续登录与越权用例使用）
    // ==================================================================

    @Test
    @Order(1)
    void 注册买家A() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER_A)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13800001111"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));
    }

    @Test
    @Order(2)
    void 注册买家B() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER_B)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13800002222"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));
    }

    // ==================================================================
    // 二、未登录访问受保护资源 → 跳对应登录页并带回跳地址
    // ==================================================================

    @Test
    @Order(3)
    void 未登录访问卖家后台_跳卖家登录页() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin"));
    }

    @Test
    @Order(4)
    void 未登录访问客户管理_跳卖家登录页() throws Exception {
        mockMvc.perform(get("/admin/customers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fcustomers"));
    }

    @Test
    @Order(5)
    void 未登录访问我的订单_跳买家登录页() throws Exception {
        mockMvc.perform(get("/my/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Forders"));
    }

    @Test
    @Order(6)
    void 未登录访问买家中心_跳买家登录页() throws Exception {
        mockMvc.perform(get("/buyer/me"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fbuyer%2Fme"));
    }

    // ==================================================================
    // 三、分别登录，拿两套独立登录态
    // ==================================================================

    @Test
    @Order(7)
    void 买家A登录拿cookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", BUYER_A)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/me"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "买家登录必须下发 BUYER_SESSION");
        buyerACookieValue = setCookie.substring("BUYER_SESSION=".length(), setCookie.indexOf(';'));
    }

    @Test
    @Order(8)
    void 卖家登录拿session() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andReturn();

        sellerSession = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(sellerSession, "卖家登录必须创建 Session");
    }

    // ==================================================================
    // 四、两套登录态互不越界
    // ==================================================================

    @Test
    @Order(9)
    void 买家cookie访问卖家后台_被卖家拦截器拦下() throws Exception {
        // 带买家 cookie 但没有卖家 session，LoginInterceptor 看不到 LOGIN_SELLER → 拦下
        mockMvc.perform(get("/admin").cookie(buyerACookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin"));
    }

    @Test
    @Order(10)
    void 卖家session访问买家中心_被买家拦截器拦下() throws Exception {
        // 带卖家 session 但没有 BUYER_SESSION cookie，BuyerLoginInterceptor 拦下
        mockMvc.perform(get("/buyer/me").session(sellerSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fbuyer%2Fme"));
    }

    // ==================================================================
    // 五、水平越权：买家 A 冒充买家 B → 被当未登录处理，不泄露 B 的资料
    // ==================================================================

    @Test
    @Order(11)
    void 买家A篡改cookie冒充买家B访问资料_被拒且不泄露() throws Exception {
        mockMvc.perform(get("/buyer/me").cookie(forgedCookieOfB()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fbuyer%2Fme"));
    }

    @Test
    @Order(12)
    void 买家A篡改cookie冒充买家B访问订单_被拒且不泄露() throws Exception {
        mockMvc.perform(get("/my/orders").cookie(forgedCookieOfB()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Forders"));
    }

    @Test
    @Order(13)
    void 买家A正常访问买家中心_只能看到自己() throws Exception {
        MvcResult result = mockMvc.perform(get("/buyer/me").cookie(buyerACookie()))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/me"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(BUYER_A)))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains(BUYER_B),
                "买家 A 的资料页不得出现买家 B 的用户名，防止水平越权泄露");
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /** 复用买家 A 登录时拿到的合法 cookie */
    private Cookie buyerACookie() {
        assertNotNull(buyerACookieValue, "前置用例必须已完成买家 A 登录");
        return new Cookie("BUYER_SESSION", buyerACookieValue);
    }

    /**
     * 伪造一个「冒充买家 B」的 cookie。
     *
     * <p>把买家 A 的 cookie 里的 buyerId 换成买家 B 的 id，签名段随意填——
     * 模拟用户手改 cookie 冒充他人。HMAC 签名必然校验失败，系统会把它当未登录处理。</p>
     */
    private Cookie forgedCookieOfB() {
        Buyer buyerB = buyerMapper.selectByUsername(BUYER_B);
        assertNotNull(buyerB, "前置用例必须已注册买家 B");
        return new Cookie("BUYER_SESSION", buyerB.getId() + ":9999999999999:deadbeef");
    }
}

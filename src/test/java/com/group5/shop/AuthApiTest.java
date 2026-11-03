package com.group5.shop;

import com.group5.shop.auth.domain.Seller;
import com.group5.shop.auth.domain.SellerMapper;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.common.constant.SessionKeys;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 账号模块（auth）接口测试：卖家登录、卖家改密、买家注册、买家登录、买家退出。
 *
 * <p>与 {@code BuyerAuthTest} 同样的口径：一律走 MockMvc，经过真实的拦截器、业务服务与模板渲染，
 * 因为「登录态写入」「原密码校验」「密码哈希轮换」这些规则只有走完整链路才验证得了。</p>
 *
 * <p>本类使用独立临时数据库（{@code target/test-auth-api-*.db}），不影响其他测试类。
 * 特别注意：改密用例会真实修改种子账号 {@code admin} 的密码，因此<b>依赖 {@code @Order} 保证执行顺序</b>——
 * 先做完登录与两次「被拒绝」的改密，最后才真正改密并验证新旧密码的登录结果。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthApiTest {

    /** 本测试类专用数据库 */
    private static final Path TEST_DB =
            Path.of("target", "test-auth-api-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    /** 种子卖家账号（00-base.sql 预置） */
    private static final String SELLER_USERNAME = "admin";
    private static final String SELLER_PASSWORD = "admin12345";

    /** 改密后的新密码（满足 ≥8 位） */
    private static final String NEW_SELLER_PASSWORD = "adminNew12345";

    /** 测试用买家用户名，带时间戳避免与既有数据冲突 */
    private static final String BUYER_USERNAME = "buyerApi" + System.nanoTime() % 100000;

    private static final String BUYER_PASSWORD = "buyer12345";

    /** 卖家登录会话，供改密用例复用 */
    private static MockHttpSession sellerSession;

    /** 卖家账号「改密前」的密码哈希与盐，用于断言「被拒绝时不得落库」 */
    private static String originalSellerHash;
    private static String originalSellerSalt;

    /** 买家登录后拿到的 cookie 值，供退出用例复用 */
    private static String buyerCookieValue;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SellerMapper sellerMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    // ==================================================================
    // 一、卖家登录（FR-01-1）
    // ==================================================================

    @Test
    @Order(1)
    void 卖家登录成功_写Session并跳后台() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", SELLER_USERNAME)
                        .param("password", SELLER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "登录成功后必须创建 Session");
        assertEquals(SELLER_USERNAME, session.getAttribute(SessionKeys.LOGIN_SELLER),
                "Session 必须写入当前卖家用户名");
        assertEquals(1L, session.getAttribute(SessionKeys.LOGIN_SELLER_ID),
                "Session 必须写入当前卖家主键 id");

        // 保存会话与改密前的哈希/盐，供后续「先登录再改密」「拒绝不改库」用例复用
        sellerSession = session;
        Seller seller = sellerMapper.selectByUsername(SELLER_USERNAME);
        assertNotNull(seller, "种子账号必须存在");
        originalSellerHash = seller.getPasswordHash();
        originalSellerSalt = seller.getSalt();
    }

    @Test
    @Order(2)
    void 卖家登录_密码错误_回登录页并提示() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", SELLER_USERNAME)
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(3)
    void 卖家登录_空参数_回登录页并提示() throws Exception {
        mockMvc.perform(post("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    // ==================================================================
    // 二、卖家改密（FR-01-4 / FR-01-5）
    // ==================================================================

    @Test
    @Order(4)
    void 卖家改密_原密码错误_拒绝且不改库() throws Exception {
        mockMvc.perform(post("/admin/password").session(sellerSession)
                        .param("oldPassword", "definitely-wrong")
                        .param("newPassword", NEW_SELLER_PASSWORD)
                        .param("confirmPassword", NEW_SELLER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                // 失败走 ?error=，成功才走 ?msg=
                .andExpect(redirectedUrlPattern("/admin?error=*"));

        Seller seller = sellerMapper.selectByUsername(SELLER_USERNAME);
        assertEquals(originalSellerHash, seller.getPasswordHash(), "原密码错误时不得修改密码哈希");
        assertEquals(originalSellerSalt, seller.getSalt(), "原密码错误时不得轮换盐");
    }

    @Test
    @Order(5)
    void 卖家改密_新密码不足8位_拒绝且不改库() throws Exception {
        mockMvc.perform(post("/admin/password").session(sellerSession)
                        .param("oldPassword", SELLER_PASSWORD)
                        .param("newPassword", "short")
                        .param("confirmPassword", "short"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?error=*"));

        Seller seller = sellerMapper.selectByUsername(SELLER_USERNAME);
        assertEquals(originalSellerHash, seller.getPasswordHash(), "新密码过短时不得修改密码哈希");
        assertEquals(originalSellerSalt, seller.getSalt(), "新密码过短时不得轮换盐");
    }

    @Test
    @Order(6)
    void 卖家改密_成功_轮换盐并落地新哈希() throws Exception {
        mockMvc.perform(post("/admin/password").session(sellerSession)
                        .param("oldPassword", SELLER_PASSWORD)
                        .param("newPassword", NEW_SELLER_PASSWORD)
                        .param("confirmPassword", NEW_SELLER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?msg=*"));

        Seller seller = sellerMapper.selectByUsername(SELLER_USERNAME);
        assertNotEquals(originalSellerHash, seller.getPasswordHash(), "改密成功后密码哈希必须改变");
        assertNotEquals(originalSellerSalt, seller.getSalt(), "改密成功后必须轮换盐");
    }

    @Test
    @Order(7)
    void 改密后_旧密码登录失败() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", SELLER_USERNAME)
                        .param("password", SELLER_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(8)
    void 改密后_新密码登录成功() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", SELLER_USERNAME)
                        .param("password", NEW_SELLER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    // ==================================================================
    // 三、买家注册（A.2.1）
    // ==================================================================

    @Test
    @Order(9)
    void 买家注册成功_跳登录页且库中无明文密码() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER_USERNAME)
                        .param("password", BUYER_PASSWORD)
                        .param("confirmPassword", BUYER_PASSWORD)
                        .param("phone", "13800009999")
                        .param("defaultPlace", "校门口"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));

        Buyer saved = buyerMapper.selectByUsername(BUYER_USERNAME);
        assertNotNull(saved, "注册后应能在库里查到该买家");
        assertFalse(saved.getPasswordHash().contains(BUYER_PASSWORD), "库里不得出现明文密码");
        assertEquals("13800009999", saved.getPhone());
        assertEquals("校门口", saved.getDefaultPlace());
        assertNotNull(saved.getSalt(), "注册必须生成随机盐");
    }

    @Test
    @Order(10)
    void 买家注册_用户名重复_拒绝() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER_USERNAME)
                        .param("password", BUYER_PASSWORD)
                        .param("confirmPassword", BUYER_PASSWORD)
                        .param("phone", "13800008888"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(model().attribute("error", "用户名已被注册，请换一个"));

        assertEquals(1, buyerMapper.countByUsername(BUYER_USERNAME), "重复注册不得写入第二条");
    }

    @Test
    @Order(11)
    void 买家注册_缺联系电话_拒绝() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER_USERNAME + "missing")
                        .param("password", BUYER_PASSWORD)
                        .param("confirmPassword", BUYER_PASSWORD)
                        .param("phone", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(model().attribute("error", "请填写联系电话"));

        assertNull(buyerMapper.selectByUsername(BUYER_USERNAME + "missing"), "校验失败不得写库");
    }

    // ==================================================================
    // 四、买家登录 / 退出（A.2.1）
    // ==================================================================

    @Test
    @Order(12)
    void 买家登录_密码错误_回登录页并提示() throws Exception {
        mockMvc.perform(post("/buyer/login")
                        .param("username", BUYER_USERNAME)
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(13)
    void 买家登录成功_下发独立cookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", BUYER_USERNAME)
                        .param("password", BUYER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/me"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "登录成功后必须下发 BUYER_SESSION");
        assertTrue(setCookie.startsWith("BUYER_SESSION="), "cookie 名必须是 BUYER_SESSION，不能与卖家共用");

        buyerCookieValue = setCookie.substring("BUYER_SESSION=".length(), setCookie.indexOf(';'));
        assertFalse(buyerCookieValue.isBlank());
    }

    @Test
    @Order(14)
    void 买家退出_清除cookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/buyer/logout").cookie(buyerCookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "退出登录必须回写 cookie");
        assertTrue(setCookie.contains("Max-Age=0"), "退出登录必须把 cookie 置为过期：" + setCookie);
    }

    /** 复用登录时拿到的买家 cookie */
    private Cookie buyerCookie() {
        assertNotNull(buyerCookieValue, "前置用例必须已完成买家登录");
        return new Cookie("BUYER_SESSION", buyerCookieValue);
    }
}

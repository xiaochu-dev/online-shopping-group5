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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * T2-02 / T2-03 / T2-04 验收测试：买家注册、登录、两套登录态隔离、客户管理。
 *
 * <p>与 {@code AuthFlowTest} 同样的口径：一律走 MockMvc，经过真实的拦截器与模板渲染，
 * 因为这些验收标准（「未登录被拦截」「两边同时登录互不影响」）只有经过拦截器才验证得了。</p>
 *
 * <p>本类使用独立临时数据库（{@code target/test-buyer-*.db}），不影响其他测试类。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BuyerAuthTest {

    /** 本测试类专用数据库 */
    private static final Path TEST_DB =
            Path.of("target", "test-buyer-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    /** 测试用买家用户名，带时间戳避免与既有数据冲突 */
    private static final String USERNAME = "buyer" + System.nanoTime() % 100000;

    private static final String PASSWORD = "buyer12345";

    /** 登录后拿到的买家 cookie 值，供后续用例复用 */
    private static String buyerCookieValue;

    /** 卖家登录会话，用于验证两套登录态互不影响 */
    private static MockHttpSession sellerSession;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BuyerMapper buyerMapper;

    // ==================================================================
    // 一、注册（T2-02，A.2.1）
    // ==================================================================

    @Test
    @Order(1)
    void 注册页可正常渲染() throws Exception {
        mockMvc.perform(get("/buyer/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("买家注册")));
    }

    @Test
    @Order(2)
    void 注册成功后跳转登录页且库中无明文密码() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", USERNAME)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13800001111")
                        .param("defaultPlace", "图书馆门口"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));

        Buyer saved = buyerMapper.selectByUsername(USERNAME);
        assertNotNull(saved, "注册后应能在库里查到该买家");
        assertFalse(saved.getPasswordHash().contains(PASSWORD), "库里不得出现明文密码");
        assertEquals("13800001111", saved.getPhone());
        assertEquals("图书馆门口", saved.getDefaultPlace());
        assertNotNull(saved.getSalt());
    }

    @Test
    @Order(3)
    void 同一用户名二次注册应被拒绝() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", USERNAME)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13900002222"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(model().attribute("error", "用户名已被注册，请换一个"));

        assertEquals(1, buyerMapper.countByUsername(USERNAME), "重复注册不得写入第二条");
    }

    @Test
    @Order(4)
    void 密码少于8位应被拒绝() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", USERNAME + "x")
                        .param("password", "short")
                        .param("confirmPassword", "short")
                        .param("phone", "13800003333"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(model().attribute("error", "新密码长度不得少于 8 位"));

        assertNull(buyerMapper.selectByUsername(USERNAME + "x"), "校验失败不得写库");
    }

    @Test
    @Order(5)
    void 电话为空应被拒绝() throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", USERNAME + "y")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/register"))
                .andExpect(model().attribute("error", "请填写联系电话"));
    }

    @Test
    @Order(6)
    void 电话可重复填_同一号码可注册多个账号() throws Exception {
        String another = USERNAME + "z";
        mockMvc.perform(post("/buyer/register")
                        .param("username", another)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13800001111"))
                .andExpect(status().is3xxRedirection());

        assertNotNull(buyerMapper.selectByUsername(another), "电话不唯一，同号可注册第二个账号");
    }

    // ==================================================================
    // 二、登录与两套登录态（T2-03，A.2.1）
    // ==================================================================

    @Test
    @Order(7)
    void 密码错误应停留在买家登录页并提示() throws Exception {
        mockMvc.perform(post("/buyer/login")
                        .param("username", USERNAME)
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(8)
    void 正确凭据应登录成功并下发独立cookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/me"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "登录成功后必须下发 BUYER_SESSION");
        assertTrue(setCookie.startsWith("BUYER_SESSION="), "cookie 名必须是 BUYER_SESSION，不能与卖家共用");
        assertTrue(setCookie.contains("HttpOnly"), "登录 cookie 必须 HttpOnly");

        buyerCookieValue = setCookie.substring("BUYER_SESSION=".length(), setCookie.indexOf(';'));
        assertFalse(buyerCookieValue.isBlank());
    }

    @Test
    @Order(9)
    void 登录后可进入买家中心并看到自己的信息() throws Exception {
        mockMvc.perform(get("/buyer/me").cookie(buyerCookie()))
                .andExpect(status().isOk())
                .andExpect(view().name("buyer/me"))
                .andExpect(model().attributeExists("buyer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(USERNAME)));
    }

    @Test
    @Order(10)
    void 未登录访问我的订单应跳买家登录页() throws Exception {
        mockMvc.perform(get("/my/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Forders"));
    }

    // ==================================================================
    // 三、客户管理（T2-04，A.2.3）与越权
    // ==================================================================

    @Test
    @Order(11)
    void 未登录访问客户管理应跳卖家登录页() throws Exception {
        mockMvc.perform(get("/admin/customers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fcustomers"));
    }

    @Test
    @Order(12)
    void 买家cookie不能访问卖家后台_越权应被拒() throws Exception {
        mockMvc.perform(get("/admin/customers").cookie(buyerCookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fcustomers"));
    }

    @Test
    @Order(13)
    void 卖家登录后可看到客户列表且含刚注册的客户() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin12345"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        sellerSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/admin/customers").session(sellerSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customers"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(USERNAME)));
    }

    @Test
    @Order(14)
    void 客户列表支持按用户名检索() throws Exception {
        mockMvc.perform(get("/admin/customers").session(sellerSession)
                        .param("keyword", USERNAME))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("pageResult"));
    }

    @Test
    @Order(15)
    void 客户详情页应展示基本信息与购买历史区() throws Exception {
        Buyer buyer = buyerMapper.selectByUsername(USERNAME);
        mockMvc.perform(get("/admin/customers/" + buyer.getId()).session(sellerSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customer-detail"))
                .andExpect(model().attribute("historyReady", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("13800001111")));
    }

    @Test
    @Order(16)
    void 两边同时登录互不踢号() throws Exception {
        // 买家 cookie + 卖家 session 同时带上，两个页面都应可访问
        mockMvc.perform(get("/buyer/me").cookie(buyerCookie()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin").session(sellerSession))
                .andExpect(status().isOk());
    }

    @Test
    @Order(17)
    void 伪造或篡改的买家cookie应被拒绝() throws Exception {
        Buyer buyer = buyerMapper.selectByUsername(USERNAME);
        // 签名段随意填，模拟用户手改 cookie 冒充他人
        Cookie forged = new Cookie("BUYER_SESSION", buyer.getId() + ":9999999999999:deadbeef");
        mockMvc.perform(get("/buyer/me").cookie(forged))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fbuyer%2Fme"));
    }

    @Test
    @Order(18)
    void 退出登录后cookie被清除() throws Exception {
        MvcResult result = mockMvc.perform(get("/buyer/logout").cookie(buyerCookie()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buyer/login"))
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Max-Age=0"), "退出登录必须把 cookie 置为过期：" + setCookie);
    }

    /** 复用登录时拿到的买家 cookie */
    private Cookie buyerCookie() {
        assertNotNull(buyerCookieValue, "前置用例必须已完成登录");
        return new Cookie("BUYER_SESSION", buyerCookieValue);
    }
}

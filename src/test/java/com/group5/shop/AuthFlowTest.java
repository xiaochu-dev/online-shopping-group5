package com.group5.shop;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * T2-05 验收测试：登录 / 拦截 / 改密。
 *
 * <p>用 MockMvc 走真实的 DispatcherServlet + 拦截器 + 模板渲染链路，
 * 不是直接调 Service —— 因为「未登录被拦截」这条验收标准只有经过拦截器才验证得了。</p>
 *
 * <p>方法上的 {@code @Order} 是刻意保留的：改密测试会真的把密码改掉，
 * 必须排在「用旧密码登录」之后，否则后续用例全部失败。
 * 每个测试类使用独立的临时数据库文件，互不干扰。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest {

    /** 本测试类专用数据库，路径带时间戳，避免多次运行互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-auth-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private MockMvc mockMvc;

    /** 已登录会话，供改密用例复用 */
    private static MockHttpSession loggedInSession;

    // ==================================================================
    // 一、登录拦截（FR-01-3）
    // ==================================================================

    @Test
    @Order(1)
    void 未登录访问后台应被重定向到登录页() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin"));
    }

    @Test
    @Order(2)
    void 未登录访问后台子页面同样被拦截() throws Exception {
        mockMvc.perform(get("/admin/diagnostics"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fdiagnostics"));
    }

    @Test
    @Order(3)
    void 接口请求未登录应返回401JSON而不是302() throws Exception {
        mockMvc.perform(get("/admin").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("登录已过期，请重新登录"));
    }

    // ==================================================================
    // 二、登录页与登录校验（FR-01-1）
    // ==================================================================

    @Test
    @Order(4)
    void 登录页可正常渲染并含默认账号提示() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("卖家后台登录")));
    }

    @Test
    @Order(5)
    void 错误密码应停留在登录页并提示() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(6)
    void 不存在的用户名与错误密码提示应完全一致() throws Exception {
        // 防止「用户名枚举」：两种情况提示语必须一样
        mockMvc.perform(post("/login")
                        .param("username", "nobody")
                        .param("password", "whatever"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", "用户名或密码错误"));
    }

    @Test
    @Order(7)
    void 正确凭据应登录成功并跳转后台() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andReturn();

        loggedInSession = (MockHttpSession) result.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(loggedInSession, "登录成功后必须创建会话");
    }

    @Test
    @Order(8)
    void 登录后可正常打开后台首页() throws Exception {
        mockMvc.perform(get("/admin").session(loggedInSession))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/admin-home"))
                .andExpect(model().attribute("username", "admin"))
                // 种子数据：5 个分类
                .andExpect(model().attribute("categoryCount", 5L));
    }

    @Test
    @Order(9)
    void 已登录再访问登录页应直接跳后台() throws Exception {
        mockMvc.perform(get("/login").session(loggedInSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    // ==================================================================
    // 三、修改密码（FR-01-4 / FR-01-5）
    // ==================================================================

    @Test
    @Order(10)
    void 原密码错误时拒绝改密() throws Exception {
        mockMvc.perform(post("/admin/password").session(loggedInSession)
                        .param("oldPassword", "not-my-password")
                        .param("newPassword", "newPassword123")
                        .param("confirmPassword", "newPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?error=*"));
    }

    @Test
    @Order(11)
    void 新密码不足8位时拒绝改密() throws Exception {
        mockMvc.perform(post("/admin/password").session(loggedInSession)
                        .param("oldPassword", "admin12345")
                        .param("newPassword", "abc123")
                        .param("confirmPassword", "abc123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?error=*"));
    }

    @Test
    @Order(12)
    void 两次新密码不一致时拒绝改密() throws Exception {
        mockMvc.perform(post("/admin/password").session(loggedInSession)
                        .param("oldPassword", "admin12345")
                        .param("newPassword", "newPassword123")
                        .param("confirmPassword", "newPassword999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?error=*"));
    }

    @Test
    @Order(13)
    void 原密码正确且新密码合规时改密成功() throws Exception {
        mockMvc.perform(post("/admin/password").session(loggedInSession)
                        .param("oldPassword", "admin12345")
                        .param("newPassword", "newPassword123")
                        .param("confirmPassword", "newPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin?msg=*"));
    }

    @Test
    @Order(14)
    void 改密后旧密码失效新密码可用() throws Exception {
        // 旧密码应被拒绝
        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "admin12345"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", "用户名或密码错误"));

        // 新密码应登录成功
        mockMvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "newPassword123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    @Order(15)
    void 退出登录后会话失效() throws Exception {
        mockMvc.perform(get("/logout").session(loggedInSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        // 旧会话对象已被 invalidate，再用它访问应被拦截
        mockMvc.perform(get("/admin").session(loggedInSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fadmin"));
    }
}

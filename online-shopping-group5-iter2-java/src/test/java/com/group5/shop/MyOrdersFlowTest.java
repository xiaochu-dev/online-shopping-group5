package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.service.BuyerAuthService;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * T2-17 验收测试：我的订单。
 *
 * <p>三条必须钉住的规则：<b>只显示有结果的单</b>（排队中不进列表）、
 * <b>按交易时间倒序</b>、<b>换号看不到别人的单</b>。</p>
 *
 * <p>本用例走<b>真实登录</b>（注册 → 登录 → 带 BUYER_SESSION cookie 访问），
 * 而不是 Mock 掉 BuyerContext：{@code /my/**} 由账号模块的拦截器把守，
 * 只有真实 cookie 才能验证「未登录被拦、登录后不串号」这两条端到端行为。</p>
 *
 * @author 周到
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyOrdersFlowTest {

    private static final Path TEST_DB =
            Path.of("target", "test-orders-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    /** 买家登录 cookie 名，与账号模块约定一致 */
    private static final String COOKIE_NAME = "BUYER_SESSION";

    private static final String PASSWORD = "pass12345";
    private static final String USER_A = "orders_buyer_a";
    private static final String USER_B = "orders_buyer_b";

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private IntentMapper intentMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BuyerAuthService buyerAuthService;

    @Autowired
    private BuyerMapper buyerMapper;

    private static Long buyerAId;
    private static Long buyerBId;

    /** 较早成交的商品 */
    private static Long earlyProductId;
    /** 较晚成交的商品 */
    private static Long lateProductId;
    /** 仍在排队、不该出现在历史里的商品 */
    private static Long pendingProductId;

    @BeforeAll
    void seed() {
        // 账号模块的正式注册接口：密码规则、加盐哈希都由它保证
        buyerAId = buyerAuthService.register(USER_A, PASSWORD, "13800002222", "图书馆门口").getId();
        buyerBId = buyerAuthService.register(USER_B, PASSWORD, "13800003333", "东门快递点").getId();

        List<Category> categories = categoryMapper.selectEnabled();
        Long categoryId = categories.get(0).getId();

        earlyProductId = insertProduct("成交较早的台灯", categoryId);
        lateProductId = insertProduct("成交较晚的椅子", categoryId);
        pendingProductId = insertProduct("还在排队的加湿器", categoryId);

        // 失败的早、成功的晚：用来验证「按交易时间倒序」而不是按主键顺/逆序
        insertIntent(buyerAId, earlyProductId, IntentStatus.FAILED, 1_700_000_000_000L);
        insertIntent(buyerAId, lateProductId, IntentStatus.SUCCESS, 1_700_000_900_000L);

        // 排队中的单：不应进入历史列表
        insertIntent(buyerAId, pendingProductId, IntentStatus.PENDING, null);
    }

    private Long insertProduct(String name, Long categoryId) {
        Product p = new Product();
        p.setSellerId(1L);
        p.setCategoryId(categoryId);
        p.setName(name);
        p.setDescription("测试商品");
        p.setImagePath("images/placeholder.png");
        p.setPriceCents(10000L);
        p.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        p.setPublishedAt(now);
        p.setUpdatedAt(now);
        productMapper.insert(p);
        return p.getId();
    }

    private void insertIntent(Long buyerId, Long productId, String status, Long finishedAt) {
        Buyer buyer = buyerMapper.selectById(buyerId);
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(buyerId);
        intent.setBuyerName(buyer.getUsername());
        intent.setPhone(buyer.getPhone());
        intent.setTradeAddress(buyer.getDefaultPlace());
        intent.setStatus(status);
        intent.setCreatedAt(1_600_000_000_000L);
        intent.setFinishedAt(finishedAt);
        if (IntentStatus.SUCCESS.equals(status) || IntentStatus.FAILED.equals(status)) {
            intent.setTradeResult(status);
        }
        intentMapper.insert(intent);
    }

    /** 走真实登录接口拿 BUYER_SESSION cookie */
    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("登录成功必须下发 BUYER_SESSION").isNotNull().startsWith(COOKIE_NAME + "=");
        String value = setCookie.substring((COOKIE_NAME + "=").length(), setCookie.indexOf(';'));
        return new Cookie(COOKIE_NAME, value);
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 未登录访问我的订单跳买家登录页() throws Exception {
        mockMvc.perform(get("/my/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));
    }

    @Test
    void 只显示已有结果的订单且按交易时间倒序() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_A)))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/my-orders"))
                .andExpect(model().attributeExists("pageResult"))
                .andReturn());

        assertThat(html).contains("成交较早的台灯");
        assertThat(html).contains("成交较晚的椅子");
        assertThat(html).contains("交易失败");
        assertThat(html).contains("交易成功");

        // 排队中的单不进历史
        assertThat(html).doesNotContain("还在排队的加湿器");

        // 交易时间倒序：成交晚的椅子必须排在成交早的台灯前面
        assertThat(html.indexOf("成交较晚的椅子"))
                .as("按交易时间倒序")
                .isLessThan(html.indexOf("成交较早的台灯"));
    }

    @Test
    void 换号看不到别人的订单() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_B)))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("还没有成交记录");
        assertThat(html).doesNotContain("成交较早的台灯");
    }
}

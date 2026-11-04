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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代 3 新增：买家「进行中意向」可见 + 自助撤销 的验收测试。
 *
 * <p>补上原设计的缺口——原口径只让客户看到已出结果的单，客户提交意向后
 * 既查不到、也撤不了，等于进入黑箱。</p>
 *
 * <p>四条必须钉住的规则：<b>排队中可见</b>、<b>仅本人可撤销</b>、
 * <b>仅排队中可撤销</b>、<b>撤销后退出队列</b>。其中「仅本人」是安全底线，
 * 用带 {@code buyer_id} 条件的原子 UPDATE 保证，不靠应用层先查后改。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyIntentCancelTest {

    private static final Path TEST_DB =
            Path.of("target", "test-cancel-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    private static final String COOKIE_NAME = "BUYER_SESSION";
    private static final String PASSWORD = "pass12345";
    private static final String USER_A = "cancel_buyer_a";
    private static final String USER_B = "cancel_buyer_b";

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
    private static Long categoryId;

    @BeforeAll
    void seed() {
        buyerAId = buyerAuthService.register(USER_A, PASSWORD, "13900001111", "图书馆门口").getId();
        buyerBId = buyerAuthService.register(USER_B, PASSWORD, "13900002222", "东门快递点").getId();
        List<Category> categories = categoryMapper.selectEnabled();
        categoryId = categories.get(0).getId();
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    void 排队中的意向在我的订单可见且可撤销() throws Exception {
        Long productId = insertProduct("待撤销的键盘");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.PENDING);

        // 1. 提交后能在「进行中」看到
        String html = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_A)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("进行中的意向");
        assertThat(html).contains("待撤销的键盘");
        assertThat(html).contains("撤销意向");

        // 2. 撤销成功并跳回列表
        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        // 3. 状态已变 cancelled
        assertThat(intentMapper.selectById(intentId).getStatus()).isEqualTo(IntentStatus.CANCELLED);

        // 4. 不再出现在「进行中」
        String after = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_A)))
                .andExpect(status().isOk()).andReturn());
        assertThat(after).contains("当前没有进行中的意向");
    }

    @Test
    void 不能撤销别人的意向() throws Exception {
        Long productId = insertProduct("别人家的椅子");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.PENDING);

        // B 去撤销 A 的单：必须失败
        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_B)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        // A 的单必须原封不动仍是 pending
        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("越权撤销不能改动他人单据")
                .isEqualTo(IntentStatus.PENDING);
    }

    @Test
    void 交易中的意向不可撤销() throws Exception {
        Long productId = insertProduct("交易中的台灯");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.TRADING);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("卖家已确认进入交易的单不能被买家单方面撤销")
                .isEqualTo(IntentStatus.TRADING);
    }

    @Test
    void 已成交的意向不可撤销() throws Exception {
        Long productId = insertProduct("已成交的加湿器");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.SUCCESS);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("已成交的单是终态，不可撤销")
                .isEqualTo(IntentStatus.SUCCESS);
    }

    @Test
    void 未登录不能撤销() throws Exception {
        Long productId = insertProduct("未登录测试品");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.PENDING);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));

        assertThat(intentMapper.selectById(intentId).getStatus()).isEqualTo(IntentStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Long insertProduct(String name) {
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

    private Long insertIntent(Long buyerId, Long productId, String status) {
        Buyer buyer = buyerMapper.selectById(buyerId);
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(buyerId);
        intent.setBuyerName(buyer.getUsername());
        intent.setPhone(buyer.getPhone());
        intent.setTradeAddress(buyer.getDefaultPlace());
        intent.setStatus(status);
        intent.setCreatedAt(1_600_000_000_000L);
        if (IntentStatus.SUCCESS.equals(status) || IntentStatus.FAILED.equals(status)) {
            intent.setTradeResult(status);
            intent.setFinishedAt(1_700_000_000_000L);
        }
        intentMapper.insert(intent);
        return intent.getId();
    }

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
}

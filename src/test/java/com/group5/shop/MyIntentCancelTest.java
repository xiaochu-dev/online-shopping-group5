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
 * B.1.2 验收测试：客户在「开始发货」之前可以取消订单。
 *
 * <p>迭代 3 的这份测试测的是「仅排队中可撤销、卖家确认后客户就无权反悔」，
 * 与新口径<b>正好相反</b>：B 明确要求客户在 created / confirmed / prepared 三态都能取消，
 * 只有到 shipped 之后才关上这道门。所以边界点从「卖家确认」移到了「开始发货」。</p>
 *
 * <p>四条必须钉住的规则：<b>进行中可见</b>、<b>仅本人可取消</b>、
 * <b>发货前可取消（含已确认、已备货）</b>、<b>发货后不可取消</b>。</p>
 *
 * @author 周到（迭代 4 · B.1.2）
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
    void 进行中的订单在我的订单可见且可取消() throws Exception {
        Long productId = insertProduct("待取消的键盘");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.CREATED);

        // 1. 下单后能在「进行中」看到，并且有取消入口
        String html = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_A)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("进行中的订单");
        assertThat(html).contains("待取消的键盘");
        assertThat(html).contains("取消订单");

        // 2. 取消成功并跳回列表
        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        // 3. 状态变成 cancelled，并且记下了取消方与时间
        Intent cancelled = intentMapper.selectById(intentId);
        assertThat(cancelled.getStatus()).isEqualTo(IntentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(IntentStatus.CANCELLED_BY_BUYER);
        assertThat(cancelled.getCancelledAt()).isNotNull();

        // 4. 不再出现在「进行中」
        // 5. 已取消的单退出「进行中」列表（用查询判定，避免商品名同时出现在「已结束」区块里）
        String after = bodyOf(mockMvc.perform(get("/my/orders").cookie(loginCookie(USER_A)))
                .andExpect(status().isOk()).andReturn());
        assertThat(after).isNotNull();
        assertThat(intentMapper.selectActiveByBuyer(buyerAId))
                .as("已取消的单不该再出现在进行中列表")
                .noneMatch(i -> i.getId().equals(intentId));
    }

    @Test
    void 已确认与已备货的订单客户仍可取消() throws Exception {
        // B.1.2 的关键点：关闸线在「开始发货」，不是「商家确认」
        for (String status : List.of(IntentStatus.CONFIRMED, IntentStatus.PREPARED)) {
            Long productId = insertProduct("可取消的台灯-" + status);
            Long intentId = insertIntent(buyerAId, productId, status);

            mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

            assertThat(intentMapper.selectById(intentId).getStatus())
                    .as("「%s」态仍属于发货前，客户可以取消", status)
                    .isEqualTo(IntentStatus.CANCELLED);
        }
    }

    @Test
    void 已发货的订单客户不可取消() throws Exception {
        Long productId = insertProduct("已发货的加湿器");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.SHIPPED);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("商家开始发货后客户不能再取消")
                .isEqualTo(IntentStatus.SHIPPED);
    }

    @Test
    void 已完成的订单不可取消() throws Exception {
        Long productId = insertProduct("已完成的显示器");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.COMPLETED);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_A)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("交易完成是终局，不可取消")
                .isEqualTo(IntentStatus.COMPLETED);
    }

    @Test
    void 不能取消别人的订单() throws Exception {
        Long productId = insertProduct("别人家的椅子");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.CREATED);

        // B 去取消 A 的单：必须失败
        mockMvc.perform(post("/my/orders/" + intentId + "/cancel").cookie(loginCookie(USER_B)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(intentMapper.selectById(intentId).getStatus())
                .as("越权取消不能改动他人订单")
                .isEqualTo(IntentStatus.CREATED);
    }

    @Test
    void 未登录不能取消() throws Exception {
        Long productId = insertProduct("未登录测试品");
        Long intentId = insertIntent(buyerAId, productId, IntentStatus.CREATED);

        mockMvc.perform(post("/my/orders/" + intentId + "/cancel"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));

        assertThat(intentMapper.selectById(intentId).getStatus()).isEqualTo(IntentStatus.CREATED);
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
        if (IntentStatus.isTerminal(status)) {
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

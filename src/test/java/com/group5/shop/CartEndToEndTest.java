package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.cart.domain.CartMapper;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.OrderLine;
import com.group5.shop.portal.domain.OrderLineMapper;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端验收：挑战需求 B 的「购物车 → 下单 → 五态履约」与「取消回补」（任务 T4-16）。
 *
 * <p><b>为什么不打桩</b>：这条链路上有三个模块接壤——购物车（胡成溯）、订单域（周到）、
 * 商品库存（黄程宇）。单元测试各自打桩只能证明「自己那一半对」，无法证明
 * 「三个端口拼起来还对不对」。本类一律走真实实现、真实 HTTP 入口、真实数据库。</p>
 *
 * <p><b>覆盖的两条主线</b>：</p>
 * <ol>
 *   <li><b>正向履约</b>：加购 → 结算（扣库存）→ 商家确认 → 备货完成 → 开始发货 → 交易完成；</li>
 *   <li><b>取消回补</b>：下单扣到 0 触发自动下架 → 客户取消 → 库存回补且商品恢复在售；
 *       重复取消不得二次回补（台账去重）。</li>
 * </ol>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartEndToEndTest {

    private static final Path TEST_DB =
            Path.of("target", "test-e2e-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private IntentMapper intentMapper;

    @Autowired
    private OrderLineMapper orderLineMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    private static final String PASSWORD = "Passw0rd2026";
    private static final String BUYER = "e2e_buyer";
    private static final String SELLER = "admin";
    private static final String SELLER_PASSWORD = "admin12345";

    /** 商品域没有把 off_shelf 收敛进 ProductStatus（StockBatchProvider 内部自用常量），此处同样用字面量 */
    private static final String OFF_SHELF = "off_shelf";

    private static Long buyerId;
    private static Long categoryId;

    @BeforeAll
    void seed() throws Exception {
        Category category = categoryMapper.selectEnabled().get(0);
        categoryId = category.getId();

        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13900009999")
                        .param("defaultPlace", "图书馆门口"))
                .andExpect(status().isFound());
        Buyer buyer = buyerMapper.selectByUsername(BUYER);
        buyerId = buyer.getId();
    }

    @BeforeEach
    void clearCart() {
        cartMapper.deleteByBuyer(buyerId);
    }

    // ==================================================================
    // 一、正向履约：加购 → 结算 → 四步推进 → 完成
    // ==================================================================

    @Test
    @DisplayName("多件下单后走完五态：created → confirmed → prepared → shipped → completed")
    void 下单到完成的全链路() throws Exception {
        Long productId = insertProduct("E2E 商品·履约", 1200L, 5);
        Cookie cookie = loginCookie(BUYER);

        // 1) 加购 2 件并结算
        mockMvc.perform(post("/cart/add").cookie(cookie)
                        .param("productId", String.valueOf(productId)).param("quantity", "2"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/checkout").cookie(cookie))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        // 2) 订单落库：状态「客户下单」，明细 2 件，购物车清空，库存扣到 3
        Intent order = latestOrder();
        assertThat(order.getStatus()).isEqualTo(IntentStatus.CREATED);
        List<OrderLine> lines = orderLineMapper.selectByIntentId(order.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getProductId()).isEqualTo(productId);
        assertThat(lines.get(0).getQuantity()).isEqualTo(2);
        assertThat(lines.get(0).getPriceCents()).as("成交价取的是下单时刻的商品价").isEqualTo(1200);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(3);
        assertThat(cartMapper.countByBuyer(buyerId)).as("结算成功后购物车应清空").isZero();

        // 3) 商家登录后连点四次「推进」，逐态核对
        MockHttpSession sellerSession = loginSeller();
        assertThat(advance(order.getId(), sellerSession)).isEqualTo(IntentStatus.CONFIRMED);
        assertThat(advance(order.getId(), sellerSession)).isEqualTo(IntentStatus.PREPARED);
        assertThat(advance(order.getId(), sellerSession)).isEqualTo(IntentStatus.SHIPPED);
        assertThat(advance(order.getId(), sellerSession)).isEqualTo(IntentStatus.COMPLETED);

        // 4) 完成后库存不再变化，订单明细保留
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(3);
        assertThat(orderLineMapper.selectByIntentId(order.getId())).hasSize(1);
    }

    // ==================================================================
    // 二、取消回补：扣到 0 自动下架 → 取消 → 回补并恢复在售
    // ==================================================================

    @Test
    @DisplayName("下单扣到 0 自动下架；客户取消后库存回补且商品恢复在售，重复取消不二次回补")
    void 取消订单后库存回补并恢复在售() throws Exception {
        Long productId = insertProduct("E2E 商品·取消回补", 800L, 3);
        Cookie cookie = loginCookie(BUYER);

        // 1) 买光库存：3 件全部下单
        mockMvc.perform(post("/cart/add").cookie(cookie)
                        .param("productId", String.valueOf(productId)).param("quantity", "3"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/checkout").cookie(cookie))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        Intent order = latestOrder();
        Product afterDeduct = productMapper.selectById(productId);
        assertThat(afterDeduct.getStock()).as("下单即扣：3 - 3 = 0").isZero();
        assertThat(afterDeduct.getStatus())
                .as("扣到 0 应自动下架（B 的演示点之一）").isEqualTo(OFF_SHELF);
        assertThat(afterDeduct.getOffReason()).isEqualTo("auto");

        // 2) 客户在「开始发货前」取消
        mockMvc.perform(post("/my/orders/" + order.getId() + "/cancel").cookie(cookie))
                .andExpect(status().isFound());

        Intent cancelled = intentMapper.selectById(order.getId());
        assertThat(cancelled.getStatus()).isEqualTo(IntentStatus.CANCELLED);

        Product afterRestore = productMapper.selectById(productId);
        assertThat(afterRestore.getStock()).as("取消后库存回补为 3").isEqualTo(3);
        assertThat(afterRestore.getStatus())
                .as("因库存归零被自动下架的商品，回补后要恢复在售")
                .isEqualTo(ProductStatus.ON_SALE);
        assertThat(afterRestore.getOffReason()).as("自动下架的标记应清掉").isNull();

        // 3) 再取消一次：状态已不是可取消态，必须拒绝，且库存不得二次回补
        mockMvc.perform(post("/my/orders/" + order.getId() + "/cancel").cookie(cookie))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?error=*"));

        assertThat(productMapper.selectById(productId).getStock())
                .as("重复取消绝不能把库存越补越多").isEqualTo(3);
    }

    // ==================================================================
    // 三、多件订单的卖家可见性：同一张单在它涉及的每个商品页都能看到
    // ==================================================================

    @Test
    @DisplayName("两件商品一起结算是一张单；卖家在两个商品页都看得到，且只把本商品列为主行")
    void 多件订单在每个相关商品页都能被卖家看到() throws Exception {
        Long book = insertProduct("E2E 商品·书", 1000L, 5);
        Long keyboard = insertProduct("E2E 商品·键盘", 2000L, 5);
        Cookie cookie = loginCookie(BUYER);

        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(book)).param("quantity", "2"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(keyboard)).param("quantity", "1"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/checkout").cookie(cookie))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        Intent order = latestOrder();
        assertThat(orderLineMapper.selectByIntentId(order.getId()))
                .as("一次结算 = 一张单两条明细，不是两张单").hasSize(2);

        MockHttpSession sellerSession = loginSeller();

        String onBook = mockMvc.perform(get("/admin/intents/" + book).session(sellerSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(onBook).contains(order.getOrderNo());
        assertThat(onBook).contains("E2E 商品·书 ×2");
        assertThat(onBook).contains("还含 E2E 商品·键盘 ×1");

        // 关键回归：非首件商品的订单页也必须有这张单。
        // 旧实现按 intent.product_id 过滤，这里会是一条空列表。
        String onKeyboard = mockMvc.perform(get("/admin/intents/" + keyboard).session(sellerSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(onKeyboard).contains(order.getOrderNo());
        assertThat(onKeyboard).contains("E2E 商品·键盘 ×1");
        assertThat(onKeyboard).contains("还含 E2E 商品·书 ×2");

        // 从键盘页推进：回跳仍在键盘页，整单状态同步（一张单只有一个状态）
        mockMvc.perform(post("/admin/intents/" + order.getId() + "/advance").session(sellerSession)
                        .param("productId", String.valueOf(keyboard)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + keyboard + "?msg=*"));
        assertThat(intentMapper.selectById(order.getId()).getStatus()).isEqualTo(IntentStatus.CONFIRMED);
    }

    // ==================================================================
    // 助手
    // ==================================================================

    /** 取本买家最新一笔订单（隔离库内买家唯一，取 id 最大即可） */
    private Intent latestOrder() {
        List<Intent> all = intentMapper.selectList(null);
        assertThat(all).as("应已生成订单").isNotEmpty();
        return all.stream().max((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
    }

    /** 卖家登录，返回可复用的 session（卖家后台用 session，不是 cookie） */
    private MockHttpSession loginSeller() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login").session(session)
                        .param("username", SELLER)
                        .param("password", SELLER_PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/admin"));
        return session;
    }

    /** 推进一次状态，回读并返回最新状态 */
    private String advance(Long intentId, MockHttpSession sellerSession) throws Exception {
        mockMvc.perform(post("/admin/intents/" + intentId + "/advance").session(sellerSession))
                .andExpect(status().isFound());
        return intentMapper.selectById(intentId).getStatus();
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().isFound())
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(BuyerSessionCookie.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("登录后未拿到登录态 cookie"));
        return new Cookie(BuyerSessionCookie.COOKIE_NAME,
                setCookie.substring((BuyerSessionCookie.COOKIE_NAME + "=").length()).split(";", 2)[0]);
    }

    private Long insertProduct(String name, Long priceCents, int stock) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription(name + "（端到端测试数据）");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(priceCents);
        product.setStock(stock);
        product.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return product.getId();
    }
}

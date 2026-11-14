package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.cart.domain.CartMapper;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结算成功路径的验证（挑战需求 B.2.3）。
 *
 * <p><b>为什么要用测试桩</b>：结算能力由订单域的 {@code OrderCreatePort} 提供，
 * 该模块尚未提交。购物车若只能测「未接入」的失败路径，就无法证明「端口一旦实现，
 * 购物车不需要改一行代码即可下单」——而这正是端口解耦要保证的事。</p>
 *
 * <p>本类用 {@link StubOrderPort} 顶替订单域：记录收到的明细、返回一个固定订单号。
 * 断言三件事：明细（商品 + 数量 + 参考单价）传得对、成功后跳我的订单、购物车被清空。
 * 真实订单落库由订单域的测试负责，这里只验证<b>契约边界</b>。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(CartCheckoutStubTest.StubConfig.class)
class CartCheckoutStubTest {

    private static final Path TEST_DB =
            Path.of("target", "test-cart-stub-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    /** 顶替订单域的测试桩：把收到的明细原样记下来 */
    static class StubOrderPort implements OrderCreatePort {

        private final List<String> received = new ArrayList<>();

        @Override
        public Long createOrder(Long buyerId, List<CartCheckoutLine> lines) {
            lines.forEach(line -> received.add(
                    buyerId + ":" + line.productId() + "x" + line.quantity() + "@" + line.unitPriceCents()));
            return 888_001L;
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        OrderCreatePort orderCreatePort() {
            return new StubOrderPort();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    @Autowired
    private OrderCreatePort orderCreatePort;

    /**
     * 每个用例前清空购物车与桩记录。
     *
     * <p>两个用例共用同一个买家与同一批商品，若不清场，前一个用例留下的条目会与
     * 后一个用例的加购合并（唯一索引把同商品加成一行），桩里也会残留上一次的明细——
     * 断言的「恰好两行」就会变成三行。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void resetState() {
        cartMapper.deleteByBuyer(buyerId);
        ((StubOrderPort) orderCreatePort).received.clear();
    }

    private static final String PASSWORD = "Passw0rd2026";
    private static final String BUYER = "cart_stub_buyer";
    private static Long buyerId;
    private static Long productA;
    private static Long productB;

    @BeforeAll
    void seed() throws Exception {
        Category category = categoryMapper.selectEnabled().get(0);
        productA = insertProduct("结算桩商品·甲", category.getId(), 1200L, 10);
        productB = insertProduct("结算桩商品·乙", category.getId(), 3450L, 10);

        mockMvc.perform(post("/buyer/register")
                        .param("username", BUYER)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", "13900003333")
                        .param("defaultPlace", "西门快递柜"))
                .andExpect(status().isFound());
        Buyer buyer = buyerMapper.selectByUsername(BUYER);
        buyerId = buyer.getId();
    }

    @Test
    @DisplayName("多件结算：明细传对、跳我的订单、购物车清空")
    void 多件结算走通并清空购物车() throws Exception {
        Cookie cookie = loginCookie(BUYER);

        // 两件商品分别加购，数量不同
        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(productA)).param("quantity", "2"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(productB)).param("quantity", "1"))
                .andExpect(status().isFound());

        assertThat(cartMapper.countByBuyer(buyerId)).isEqualTo(2L);

        MvcResult result = mockMvc.perform(post("/cart/checkout").cookie(cookie))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .as("跳转里应带上订单号")
                .contains("888001");

        StubOrderPort stub = (StubOrderPort) orderCreatePort;
        assertThat(stub.received)
                .as("订单域应收到两行明细：甲 2 件 @12.00、乙 1 件 @34.50")
                .containsExactlyInAnyOrder(
                        buyerId + ":" + productA + "x2@1200",
                        buyerId + ":" + productB + "x1@3450");

        assertThat(cartMapper.countByBuyer(buyerId))
                .as("下单成功后购物车应被清空").isZero();
    }

    @Test
    @DisplayName("部分结算：只结算勾选的条目，未勾选的留在购物车")
    void 只结算勾选的条目() throws Exception {
        Cookie cookie = loginCookie(BUYER);

        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(productA)).param("quantity", "1"))
                .andExpect(status().isFound());
        mockMvc.perform(post("/cart/add").cookie(cookie)
                .param("productId", String.valueOf(productB)).param("quantity", "1"))
                .andExpect(status().isFound());

        Long keepId = cartMapper.selectByBuyerAndProduct(buyerId, productB).getId();
        Long checkoutId = cartMapper.selectByBuyerAndProduct(buyerId, productA).getId();

        mockMvc.perform(post("/cart/checkout").cookie(cookie)
                        .param("cartIds", String.valueOf(checkoutId)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/orders?msg=*"));

        assertThat(cartMapper.selectById(checkoutId)).as("已结算的条目应被移除").isNull();
        assertThat(cartMapper.selectById(keepId)).as("未勾选的条目应保留").isNotNull();
    }

    // ==================================================================
    // 助手
    // ==================================================================

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

    private Long insertProduct(String name, Long categoryId, Long priceCents, int stock) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription(name + "（结算桩测试数据）");
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

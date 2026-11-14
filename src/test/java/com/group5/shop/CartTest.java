package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.cart.domain.Cart;
import com.group5.shop.cart.domain.CartMapper;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 购物车的接口契约测试（挑战需求 B.2.2 / B.2.3，owner：胡成溯）。
 *
 * <p><b>关注点</b>：加购落库的数据对不对、重复加购会不会堆行、数量边界拦不拦得住、
 * 「只能动自己的条目」是不是真的成立、跨域端口缺席时会不会把整页拖崩。</p>
 *
 * <p><b>不打桩任何身份</b>：注册、登录、签发 cookie 全走真实链路，否则
 * {@code BuyerContext} 永远返回同一个假买家，越权用例就没有意义。</p>
 *
 * <p><b>为什么结算用例断言的是「提示未接入」</b>：结算能力由订单域的
 * {@link OrderCreatePort} 提供，本组件的提供方尚未提交。按端口约定，缺席时应给出
 * 明确提示而不是 500 或静默失败；成功路径另见 {@code CartCheckoutStubTest}
 * （用测试桩顶替订单域，验证「端口一旦实现，购物车无需改动即可下单」）。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CartTest {

    /** 本测试类专用数据库，路径带时间戳，避免与其他测试类互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-cart-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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
    private ProductMapper productMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    /** 满足 MIN_PASSWORD_LENGTH = 8 */
    private static final String PASSWORD = "Passw0rd2026";
    private static final String BUYER_A = "cart_buyer_a";
    private static final String BUYER_B = "cart_buyer_b";

    private static Long buyerAId;
    private static Long buyerBId;

    /** 在售成品，用于正常加购 */
    private static Long productOnSale;
    /** 已下架成品，用于「不可购买」用例 */
    private static Long productOffShelf;

    @BeforeAll
    void seed() throws Exception {
        List<Category> categories = categoryMapper.selectEnabled();
        assertThat(categories).as("schema 应已写入种子分类").isNotEmpty();
        Long categoryId = categories.get(0).getId();

        productOnSale = insertProduct("购物车测试商品·在售", categoryId, 1990L, 10, ProductStatus.ON_SALE);
        productOffShelf = insertProduct("购物车测试商品·已下架", categoryId, 2990L, 10, ProductStatus.DELISTED);

        buyerAId = register(BUYER_A, "13900001111", "图书馆门口");
        buyerBId = register(BUYER_B, "13900002222", "教学楼门口");
    }

    // ==================================================================
    // 1. 登录门槛
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("未登录打开购物车：302 到买家登录页并带回跳地址")
    void 未登录打开购物车被拦截() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fcart"));
    }

    @Test
    @Order(2)
    @DisplayName("未登录加购：302 到登录页，且库里不产生任何条目")
    void 未登录加购不落库() throws Exception {
        long before = cartMapper.selectCount(null);

        mockMvc.perform(post("/cart/add").param("productId", String.valueOf(productOnSale))
                        .param("quantity", "1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));

        assertThat(cartMapper.selectCount(null)).as("未登录不得写入购物车").isEqualTo(before);
    }

    // ==================================================================
    // 2. 加购与数量规则
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("加购成功：落库 1 行，数量与名称/单价快照正确")
    void 加购成功并写入快照() throws Exception {
        mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_A))
                        .param("productId", String.valueOf(productOnSale))
                        .param("quantity", "3"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/products/" + productOnSale + "?msg=*"));

        Cart row = cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale);
        assertThat(row).as("加购后应有对应条目").isNotNull();
        assertThat(row.getQuantity()).isEqualTo(3);
        assertThat(row.getProductName()).as("应写入商品名快照").isEqualTo("购物车测试商品·在售");
        assertThat(row.getPriceCents()).as("应写入单价快照（分）").isEqualTo(1990L);
    }

    @Test
    @Order(4)
    @DisplayName("重复加购：数量累加，不产生第二行")
    void 重复加购累加数量() throws Exception {
        mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_A))
                        .param("productId", String.valueOf(productOnSale))
                        .param("quantity", "2"))
                .andExpect(status().isFound());

        assertThat(cartMapper.countByBuyer(buyerAId)).as("同一商品应仍只有一行").isEqualTo(1L);
        Cart row = cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale);
        assertThat(row.getQuantity()).as("3 + 2 = 5").isEqualTo(5);
    }

    @Test
    @Order(5)
    @DisplayName("数量上限：单条最多 99，累加超限时拒绝且不改动原值")
    void 超过数量上限被拒绝() throws Exception {
        // 先加到 99
        mockMvc.perform(post("/cart/" + cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale).getId()
                        + "/quantity").cookie(loginCookie(BUYER_A))
                        .param("quantity", "99"))
                .andExpect(status().isFound());

        // 再加 1 件 → 100 > 99，必须拒绝
        MvcResult result = mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_A))
                        .param("productId", String.valueOf(productOnSale))
                        .param("quantity", "1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/products/" + productOnSale + "?error=*"))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .as("提示里应说明上限")
                .contains("99");
        assertThat(cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale).getQuantity())
                .as("超限被拒后数量不得变化").isEqualTo(99);
    }

    @Test
    @Order(6)
    @DisplayName("数量非法：0 与 100 都被拒绝")
    void 非法数量被拒绝() throws Exception {
        Long cartId = cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale).getId();

        mockMvc.perform(post("/cart/" + cartId + "/quantity").cookie(loginCookie(BUYER_A))
                        .param("quantity", "0"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?error=*"));

        mockMvc.perform(post("/cart/" + cartId + "/quantity").cookie(loginCookie(BUYER_A))
                        .param("quantity", "100"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?error=*"));

        assertThat(cartMapper.selectByBuyerAndProduct(buyerAId, productOnSale).getQuantity())
                .as("非法数量不得落库").isEqualTo(99);
    }

    @Test
    @Order(7)
    @DisplayName("下架商品不能加购")
    void 下架商品不能加购() throws Exception {
        mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_A))
                        .param("productId", String.valueOf(productOffShelf))
                        .param("quantity", "1"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/products/" + productOffShelf + "?error=*"));

        assertThat(cartMapper.selectByBuyerAndProduct(buyerAId, productOffShelf))
                .as("下架商品不应进入购物车").isNull();
    }

    // ==================================================================
    // 3. 页面渲染
    // ==================================================================

    @Test
    @Order(8)
    @DisplayName("购物车页：200 + 视图 + 商品名与合计")
    void 购物车页正常渲染() throws Exception {
        String html = mockMvc.perform(get("/cart").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isOk())
                .andExpect(view().name("cart/list"))
                .andExpect(model().attribute("buyerName", BUYER_A))
                .andExpect(model().attributeExists("rows"))
                .andExpect(model().attributeExists("totalYuan"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("购物车测试商品·在售");
        assertThat(html).as("99 件 × 19.90 = 1970.10").contains("1970.10");
        assertThat(html).as("订单域尚未接入时应给出明确提示，而不是空白按钮").contains("结算待接入");
    }

    @Test
    @Order(9)
    @DisplayName("越权隔离：B 的购物车对 A 不可见")
    void 购物车按买家隔离() throws Exception {
        mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_B))
                        .param("productId", String.valueOf(productOnSale))
                        .param("quantity", "1"))
                .andExpect(status().isFound());

        String htmlA = mockMvc.perform(get("/cart").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(cartMapper.countByBuyer(buyerBId)).as("B 应有自己的条目").isEqualTo(1L);
        assertThat(htmlA).as("A 的页面不应出现 B 的条目 id")
                .doesNotContain("pick-" + cartMapper.selectByBuyerAndProduct(buyerBId, productOnSale).getId());
    }

    @Test
    @Order(10)
    @DisplayName("越权写操作：A 改不动 B 的条目，B 的数量保持不变")
    void 越权改数量被拒绝() throws Exception {
        Cart bRow = cartMapper.selectByBuyerAndProduct(buyerBId, productOnSale);

        mockMvc.perform(post("/cart/" + bRow.getId() + "/quantity")
                        .cookie(loginCookie(BUYER_A))
                        .param("quantity", "9"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?error=*"));

        assertThat(cartMapper.selectById(bRow.getId()).getQuantity())
                .as("B 的数量不能被 A 改动").isEqualTo(1);
    }

    // ==================================================================
    // 4. 未接入的跨域能力必须优雅降级
    // ==================================================================

    @Test
    @Order(11)
    @DisplayName("结算：订单域未接入时给出明确提示，且不清空购物车")
    void 结算在端口缺席时优雅降级() throws Exception {
        long before = cartMapper.countByBuyer(buyerAId);

        MvcResult result = mockMvc.perform(post("/cart/checkout").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?error=*"))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .as("提示应说明是「下单功能尚未接入」而不是系统错误")
                .contains("OrderCreatePort");
        assertThat(cartMapper.countByBuyer(buyerAId))
                .as("结算失败不得清空购物车").isEqualTo(before);
    }

    @Test
    @Order(12)
    @DisplayName("转收藏：收藏域未接入时给出明确提示，且不清空购物车")
    void 转收藏在端口缺席时优雅降级() throws Exception {
        long before = cartMapper.countByBuyer(buyerAId);

        MvcResult result = mockMvc.perform(post("/cart/to-favorites").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?error=*"))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).contains("FavoritePort");
        assertThat(cartMapper.countByBuyer(buyerAId)).isEqualTo(before);
    }

    @Test
    @Order(13)
    @DisplayName("移除：删掉自己的条目，行数减一")
    void 移除自己的条目() throws Exception {
        // 换一个商品，避免影响前面用例依赖的那一条
        Long another = insertProduct("购物车测试商品·可删", categoryMapper.selectEnabled().get(0).getId(),
                500L, 5, ProductStatus.ON_SALE);
        mockMvc.perform(post("/cart/add").cookie(loginCookie(BUYER_A))
                        .param("productId", String.valueOf(another))
                        .param("quantity", "1"))
                .andExpect(status().isFound());

        Cart row = cartMapper.selectByBuyerAndProduct(buyerAId, another);
        assertThat(row).isNotNull();

        mockMvc.perform(post("/cart/" + row.getId() + "/remove").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/cart?msg=*"));

        assertThat(cartMapper.selectById(row.getId())).as("移除后应查不到该行").isNull();
    }

    // ==================================================================
    // 助手
    // ==================================================================

    private Long register(String username, String phone, String defaultPlace) throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", phone)
                        .param("defaultPlace", defaultPlace))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));

        Buyer buyer = buyerMapper.selectByUsername(username);
        assertThat(buyer).as("注册成功后应能查到账号 %s", username).isNotNull();
        return buyer.getId();
    }

    /** 走真实登录端点，把 Set-Cookie 里的签名值包成请求用的 Cookie */
    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().isFound())
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(BuyerSessionCookie.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(username + " 登录后未拿到登录态 cookie"));

        String value = setCookie.substring((BuyerSessionCookie.COOKIE_NAME + "=").length())
                .split(";", 2)[0];
        return new Cookie(BuyerSessionCookie.COOKIE_NAME, value);
    }

    private Long insertProduct(String name, Long categoryId, Long priceCents, int stock, String status) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription(name + "（购物车测试数据）");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(priceCents);
        product.setStock(stock);
        product.setStatus(status);
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return product.getId();
    }
}

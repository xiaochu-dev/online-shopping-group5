package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * 买家门户的 <b>接口契约</b> 测试（T3-07 补充）。
 *
 * <p><b>与 {@link PortalFlowTest} 的分工</b>：{@code PortalFlowTest} 关心
 * 「买家在页面上看到了什么」，买家身份用 {@code @MockBean BuyerContext} 顶替；
 * 本类关心「服务到底返回了什么、有没有真的落库」，因此<b>不打桩任何身份</b>——
 * 注册、登录、签发 cookie、拦截器校验全部走真实链路。</p>
 *
 * <p>补齐的正是 {@code PortalFlowTest} 结构上覆盖不到的三块：</p>
 * <ol>
 *   <li><b>拦截器本身</b>：打桩 {@code BuyerContext} 时 {@code BuyerLoginInterceptor}
 *       永远不会拒绝请求，所以「未登录访问 /my/orders 到底跳不跳登录页」从未被验证过；</li>
 *   <li><b>响应契约</b>：状态码、Location 精确值、{@code Content-Type}、
 *       {@code Set-Cookie} 的 HttpOnly/Path 属性；</li>
 *   <li><b>越权</b>：用 A 的登录态不能看到 B 的订单——这类断言必须有真实的两套 cookie。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>重复 {@code PortalFlowTest} 已覆盖的列表/搜索/分类/详情/重复提交，
 * 那些是「拿到身份之后门户做的事」，与本类的关注点不重叠。</p>
 *
 * @author 周到
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PortalApiTest {

    /** 本测试类专用数据库，路径带时间戳，避免多次运行互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-portal-api-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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
    private CategoryMapper categoryMapper;

    @Autowired
    private IntentMapper intentMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    /** 满足 MIN_PASSWORD_LENGTH = 8 */
    private static final String PASSWORD = "Passw0rd2026";
    private static final String BUYER_A = "api_buyer_a";
    private static final String BUYER_B = "api_buyer_b";
    private static final String PHONE_A = "13800002222";
    private static final String PLACE_A = "图书馆门口";

    private static Long buyerAId;
    private static Long buyerBId;

    /** 未登录下单用（不应产生任何记录） */
    private static Long productGuest;
    /** 真实登录态下单用（验证快照与默认地点回落） */
    private static Long productOrder;
    /** 越权隔离用：A / B 各一条已成交订单 */
    private static Long productOwnA;
    private static Long productOwnB;

    @BeforeAll
    void seed() throws Exception {
        List<Category> categories = categoryMapper.selectEnabled();
        assertThat(categories).as("schema 应已写入种子分类").isNotEmpty();
        Long categoryId = categories.get(0).getId();

        productGuest = insertProduct("API 测试商品·未登录", categoryId, 12900L);
        productOrder = insertProduct("API 测试商品·真实下单", categoryId, 25900L);
        productOwnA = insertProduct("API 测试商品·A 的成交单", categoryId, 39900L);
        productOwnB = insertProduct("API 测试商品·B 的成交单", categoryId, 49900L);

        buyerAId = register(BUYER_A, PHONE_A, PLACE_A);
        buyerBId = register(BUYER_B, "13800003333", "教学楼门口");
    }

    // ==================================================================
    // 1. 拦截器：未登录访问受保护页面
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("未登录访问我的订单：302 到登录页并带回跳地址")
    void 未登录访问我的订单被拦截并带回跳地址() throws Exception {
        mockMvc.perform(get("/my/orders"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Forders"));
    }

    // ==================================================================
    // 2. 登录态本身：签名 cookie 的属性与防篡改
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("登录签发 HttpOnly 签名 cookie；改成明文 buyerId 会被拒")
    void 登录签发签名cookie且伪造cookie被拒() throws Exception {
        MvcResult result = mockMvc.perform(post("/buyer/login")
                        .param("username", BUYER_A)
                        .param("password", PASSWORD)
                        .param("redirect", "/my/orders"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/my/orders"))
                .andReturn();

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(BuyerSessionCookie.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("登录响应未签发 " + BuyerSessionCookie.COOKIE_NAME));

        assertThat(setCookie).as("登录态 cookie 必须 HttpOnly，防止 JS 读取").contains("HttpOnly");
        assertThat(setCookie).as("cookie 必须对全站可见").contains("Path=/");

        String value = setCookie.substring((BuyerSessionCookie.COOKIE_NAME + "=").length())
                .split(";", 2)[0];
        assertThat(value)
                .as("cookie 值应为 buyerId:过期毫秒:HMAC 三段，而不是裸 buyerId")
                .matches("\\d+:\\d+:[0-9a-fA-F]+");

        // 直接改 cookie 冒充买家 1：签名对不上，必须退回登录页
        mockMvc.perform(get("/my/orders")
                        .cookie(new Cookie(BuyerSessionCookie.COOKIE_NAME, "1:9999999999999:deadbeef")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Forders"));
    }

    // ==================================================================
    // 3. 带真实登录态：响应契约
    // ==================================================================

    @Test
    @Order(3)
    @DisplayName("带真实登录态打开我的订单：200 + 视图 + HTML + 当前买家")
    void 带真实登录态可打开我的订单且响应契约正确() throws Exception {
        MvcResult result = mockMvc.perform(get("/my/orders").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/my-orders"))
                .andExpect(model().attribute("buyerName", BUYER_A))
                .andExpect(model().attributeExists("pageResult"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        assertThat(result.getResponse().getCharacterEncoding())
                .as("中文页面必须是 UTF-8，否则会出现 10-22 那次乱码问题")
                .isEqualToIgnoringCase("UTF-8");
    }

    // ==================================================================
    // 4. 未登录下单：跳登录页且零副作用
    // ==================================================================

    @Test
    @Order(4)
    @DisplayName("未登录提交购买意向：302 到登录页且不产生订单")
    void 未登录提交意向被导到登录页且不落库() throws Exception {
        long before = intentMapper.selectCount(null);

        mockMvc.perform(post("/products/{id}/intents", productGuest))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fproducts%2F" + productGuest));

        assertThat(intentMapper.selectCount(null))
                .as("被拦下的请求不能留下任何意向记录")
                .isEqualTo(before);
    }

    // ==================================================================
    // 5. 带登录态下单：快照落库
    // ==================================================================

    @Test
    @Order(5)
    @DisplayName("带登录态下单：快照落库，交易地点回落到注册默认地点")
    void 带登录态下单按注册默认地点落库快照() throws Exception {
        mockMvc.perform(post("/products/{id}/intents", productOrder)
                        .cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/products/" + productOrder + "?msg=*"));

        Intent saved = intentMapper.selectQueue(productOrder).stream()
                .filter(intent -> buyerAId.equals(intent.getBuyerId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("下单成功但 intent 表没有该买家的记录"));

        assertThat(saved.getBuyerName()).as("姓名快照").isEqualTo(BUYER_A);
        assertThat(saved.getPhone()).as("电话快照").isEqualTo(PHONE_A);
        assertThat(saved.getTradeAddress())
                .as("没填交易地点时应回落到注册时的默认地点")
                .isEqualTo(PLACE_A);
        assertThat(saved.getStatus()).isEqualTo(IntentStatus.PENDING);
        assertThat(saved.getTokenCode()).as("新单不再使用口令码兜底").isNull();
    }

    // ==================================================================
    // 6. 越权隔离 + 只显示有结果的订单
    // ==================================================================

    @Test
    @Order(6)
    @DisplayName("我的订单按买家隔离，排队中的单归入「进行中」区块")
    void 我的订单按买家隔离且只显示已有结果的订单() throws Exception {
        insertFinished(buyerAId, BUYER_A, productOwnA);
        insertFinished(buyerBId, BUYER_B, productOwnB);

        String html = mockMvc.perform(get("/my/orders").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("API 测试商品·A 的成交单");
        assertThat(html).as("A 不能看到 B 的订单").doesNotContain("API 测试商品·B 的成交单");

        // 迭代 3 口径变更：排队中的单不再被隐藏，而是归入「进行中的意向」区块；
        // 但仍不能混进「已结束的订单」区块。用两个区块标题的位置来判定归属。
        int activeHeading = html.indexOf("进行中的意向");
        int finishedHeading = html.indexOf("已结束的订单");
        int pendingProduct = html.indexOf("API 测试商品·真实下单");
        assertThat(activeHeading).as("页面应有「进行中的意向」区块").isGreaterThan(-1);
        assertThat(finishedHeading).as("页面应有「已结束的订单」区块").isGreaterThan(-1);
        assertThat(pendingProduct)
                .as("排队中的单应显示在「进行中」区块内，而非「已结束」区块")
                .isGreaterThan(activeHeading)
                .isLessThan(finishedHeading);
    }

    // ==================================================================
    // 辅助方法
    // ==================================================================

    /** 走真实注册端点，再从库里取回主键（注册接口不返回 id） */
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

    private Long insertProduct(String name, Long categoryId, Long priceCents) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription(name + "（接口契约测试数据）");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(priceCents);
        product.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return product.getId();
    }

    private void insertFinished(Long buyerId, String buyerName, Long productId) {
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(buyerId);
        intent.setBuyerName(buyerName);
        intent.setPhone(PHONE_A);
        intent.setTradeAddress(PLACE_A);
        intent.setStatus(IntentStatus.SUCCESS);
        intent.setTradeResult(IntentStatus.SUCCESS);
        long now = System.currentTimeMillis();
        intent.setCreatedAt(now - 120_000L);
        intent.setFinishedAt(now - 60_000L);
        intentMapper.insert(intent);
    }
}

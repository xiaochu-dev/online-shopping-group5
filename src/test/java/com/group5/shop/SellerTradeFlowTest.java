package com.group5.shop;

import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.constant.SessionKeys;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * T4-04 验收测试：卖家订单管理（挑战需求 B.1.1 / B.1.3）。
 *
 * <p>迭代 3 的这份测试测的是「先到先得」：只有队首能确认、失败可塞回队尾、页面上有位次。
 * B 把一次性意向改成订单后，这些规则<b>全部作废</b>，本类改成测五态推进与商家取消边界：</p>
 * <ol>
 *   <li>五态齐全，页面上能按状态筛选（B.1.1）；</li>
 *   <li>商家每次只能推进一步，跳级 / 终局后再推进都被拒；</li>
 *   <li>商家在交易完成之前都能取消，<b>含已发货</b>；完成后不能取消（B.1.3）。</li>
 * </ol>
 *
 * @author 周到（迭代 4 · T4-04）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SellerTradeFlowTest {

    private static final Path TEST_DB =
            Path.of("target", "test-trade-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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

    private static Long productId;
    /** 走完整流程的那一单 */
    private static Long intentA;
    /** 到「开始发货」后被商家取消的那一单 */
    private static Long intentB;
    /** 一直留在「客户下单」态，用来验证筛选 */
    private static Long intentC;

    private MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder builder) {
        return builder.sessionAttr(SessionKeys.LOGIN_SELLER, "admin")
                .sessionAttr(SessionKeys.LOGIN_SELLER_ID, 1L);
    }

    @BeforeAll
    void seed() {
        List<Category> categories = categoryMapper.selectEnabled();
        Long categoryId = categories.get(0).getId();

        Product p = new Product();
        p.setSellerId(1L);
        p.setCategoryId(categoryId);
        p.setName("测试用显示器");
        p.setStock(3);
        p.setDescription("测试用商品");
        p.setImagePath("images/placeholder.png");
        p.setPriceCents(89900L);
        p.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        p.setPublishedAt(now);
        p.setUpdatedAt(now);
        productMapper.insert(p);
        productId = p.getId();

        intentA = insertIntent(9001L, "买家甲", 1_000L);
        intentB = insertIntent(9002L, "买家乙", 2_000L);
        intentC = insertIntent(9003L, "买家丙", 3_000L);
    }

    private Long insertIntent(Long buyerId, String name, long createdAt) {
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(buyerId);
        intent.setBuyerName(name);
        intent.setPhone("1380000000" + (buyerId % 10));
        intent.setTradeAddress("图书馆门口");
        intent.setStatus(IntentStatus.CREATED);
        intent.setCreatedAt(createdAt);
        intentMapper.insert(intent);
        return intent.getId();
    }

    // ==================================================================
    // 权限与页面
    // ==================================================================

    @Test
    @Order(1)
    void 未登录访问订单管理被拦截到登录页() throws Exception {
        mockMvc.perform(get("/admin/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?redirect=*"));
    }

    @Test
    @Order(2)
    void 商品列表显示未完成订单数() throws Exception {
        String html = mockMvc.perform(asSeller(get("/admin/intents")))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/seller-products"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("测试用显示器");
        assertThat(html).contains("3");
    }

    @Test
    @Order(3)
    void 订单页显示五种状态且可选按状态筛选() throws Exception {
        String html = mockMvc.perform(asSeller(get("/admin/intents/" + productId)))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/seller-intents"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // B.1.1：页面必须能区分五种状态（筛选条上都有）
        assertThat(html).contains("客户下单");
        assertThat(html).contains("商家确认");
        assertThat(html).contains("备货完成");
        assertThat(html).contains("开始发货");
        assertThat(html).contains("交易完成");
        // 队列位次随 B 退出，页面上不该再有这一列
        assertThat(html).doesNotContain("位次");
    }

    // ==================================================================
    // 五态推进
    // ==================================================================

    @Test
    @Order(4)
    void 商家确认后订单进入商家确认态() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/advance")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        assertThat(intentMapper.selectById(intentA).getStatus()).isEqualTo(IntentStatus.CONFIRMED);
    }

    @Test
    @Order(5)
    void 依次推进直到交易完成() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/advance")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));
        assertThat(intentMapper.selectById(intentA).getStatus()).isEqualTo(IntentStatus.PREPARED);

        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/advance")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));
        assertThat(intentMapper.selectById(intentA).getStatus()).isEqualTo(IntentStatus.SHIPPED);

        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/advance")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        Intent done = intentMapper.selectById(intentA);
        assertThat(done.getStatus()).isEqualTo(IntentStatus.COMPLETED);
        assertThat(done.getFinishedAt()).as("交易完成必须落结束时间").isNotNull();
    }

    @Test
    @Order(6)
    void 已完成的订单不能再推进也不能取消() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/advance")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?error=*"));
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/cancel")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?error=*"));

        assertThat(intentMapper.selectById(intentA).getStatus())
                .as("终局订单不得被任何动作改动").isEqualTo(IntentStatus.COMPLETED);
    }

    // ==================================================================
    // 商家取消（B.1.3）
    // ==================================================================

    @Test
    @Order(7)
    void 已发货的订单商家仍可取消() throws Exception {
        // 乙这单推到 shipped（created → confirmed → prepared → shipped）
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/advance")))
                    .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));
        }
        assertThat(intentMapper.selectById(intentB).getStatus()).isEqualTo(IntentStatus.SHIPPED);

        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/cancel"))
                        .param("reason", "快递停发"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        Intent cancelled = intentMapper.selectById(intentB);
        assertThat(cancelled.getStatus()).as("B.1.3：完成前商家可取消，含已发货")
                .isEqualTo(IntentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(IntentStatus.CANCELLED_BY_SELLER);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getFailReason()).isEqualTo("快递停发");

        // 取消是终局：之后不能再推进
        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/advance")))
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?error=*"));
        assertThat(intentMapper.selectById(intentB).getStatus()).isEqualTo(IntentStatus.CANCELLED);
    }

    @Test
    @Order(8)
    void 按状态筛选只显示该状态的订单() throws Exception {
        // 此时：甲 completed、乙 cancelled、丙 created
        String onlyCreated = mockMvc.perform(
                        asSeller(get("/admin/intents/" + productId).param("status", "created")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(onlyCreated).contains("买家丙");
        assertThat(onlyCreated).as("已完成的单不该出现在「客户下单」筛选里").doesNotContain("买家甲");
        assertThat(onlyCreated).doesNotContain("买家乙");

        String onlyCancelled = mockMvc.perform(
                        asSeller(get("/admin/intents/" + productId).param("status", "cancelled")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(onlyCancelled).contains("买家乙");
        assertThat(onlyCancelled).doesNotContain("买家丙");
    }
}

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
 * T2-18 验收测试：卖家交易确认与意向管理。
 *
 * <p>覆盖澄清结论里最容易写错的四条：
 * ① 只有队首能确认（先到先得不是口号）；
 * ② 确认后商品 {@code frozen}，其他买家不能再下单；
 * ③ 成交才扣库存、订单置 success、商品解冻；
 * ④ 失败不扣库存，买家可「作废」或「塞回队尾」，<b>全程没有独立的取消状态</b>。</p>
 *
 * <p>库存扣减在商品中心 T2-10 合入前按「不限库存」降级（{@code product.stock} 不存在），
 * 因此这里不断言库存数值，只断言订单与商品状态。</p>
 *
 * @author 周到
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
    /** 队首 */
    private static Long intentA;
    /** 第二位 */
    private static Long intentB;
    /** 第三位 */
    private static Long intentC;

    /** 已登录卖家的会话属性：直接用现有后台账号的会话键，验证与登录拦截器协同 */
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
        p.setName("只有一个的二手显示器");
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
        intent.setStatus(IntentStatus.PENDING);
        intent.setCreatedAt(createdAt);
        intentMapper.insert(intent);
        return intent.getId();
    }

    // ==================================================================
    // 权限与页面
    // ==================================================================

    @Test
    @Order(1)
    void 未登录访问意向管理被拦截到登录页() throws Exception {
        mockMvc.perform(get("/admin/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?redirect=*"));
    }

    @Test
    @Order(2)
    void 卖家商品列表显示待处理意向数() throws Exception {
        String html = mockMvc.perform(asSeller(get("/admin/intents")))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/seller-products"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("只有一个的二手显示器");
        assertThat(html).contains("3 条待处理");
    }

    // ==================================================================
    // 先到先得
    // ==================================================================

    @Test
    @Order(3)
    void 跳过队首确认被拒() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/confirm")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?error=*"));

        assertThat(intentMapper.selectById(intentB).getStatus()).isEqualTo(IntentStatus.PENDING);
    }

    @Test
    @Order(4)
    void 确认队首后进入交易中且商品冻结() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/confirm")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        assertThat(intentMapper.selectById(intentA).getStatus()).isEqualTo(IntentStatus.TRADING);
        assertThat(productMapper.selectById(productId).getStatus()).isEqualTo(ProductStatus.FROZEN);
    }

    @Test
    @Order(5)
    void 商品交易中不能同时开第二笔() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/confirm")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?error=*"));

        assertThat(intentMapper.selectById(intentB).getStatus()).isEqualTo(IntentStatus.PENDING);
    }

    // ==================================================================
    // 交易成功
    // ==================================================================

    @Test
    @Order(6)
    void 标记交易成功订单结束且商品解冻() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentA + "/success")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        Intent done = intentMapper.selectById(intentA);
        assertThat(done.getStatus()).isEqualTo(IntentStatus.SUCCESS);
        assertThat(done.getTradeResult()).isEqualTo(IntentStatus.SUCCESS);
        assertThat(done.getFinishedAt()).as("交易时间必须落库，我的订单要按它排序").isNotNull();

        assertThat(productMapper.selectById(productId).getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    // ==================================================================
    // 交易失败：作废 / 塞回队尾
    // ==================================================================

    @Test
    @Order(7)
    void 交易失败作废后商品回到在售且库存不动() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/confirm")))
                .andExpect(status().is3xxRedirection());
        assertThat(intentMapper.selectById(intentB).getStatus()).isEqualTo(IntentStatus.TRADING);

        mockMvc.perform(asSeller(post("/admin/intents/" + intentB + "/fail"))
                        .param("reason", "买家未到场"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        Intent failed = intentMapper.selectById(intentB);
        assertThat(failed.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(failed.getTradeResult()).isEqualTo(IntentStatus.FAILED);
        assertThat(failed.getFailReason()).isEqualTo("买家未到场");
        assertThat(failed.getFinishedAt()).isNotNull();

        assertThat(productMapper.selectById(productId).getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @Order(8)
    void 交易失败可把买家塞回队尾() throws Exception {
        mockMvc.perform(asSeller(post("/admin/intents/" + intentC + "/confirm")))
                .andExpect(status().is3xxRedirection());
        assertThat(intentMapper.selectById(intentC).getStatus()).isEqualTo(IntentStatus.TRADING);

        mockMvc.perform(asSeller(post("/admin/intents/" + intentC + "/fail"))
                        .param("requeue", "true")
                        .param("reason", "改约时间"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/admin/intents/" + productId + "?msg=*"));

        Intent back = intentMapper.selectById(intentC);
        assertThat(back.getStatus()).as("塞回队尾 = 回到排队中").isEqualTo(IntentStatus.PENDING);
        assertThat(back.getFinishedAt()).as("重新排队不算结束").isNull();
        assertThat(back.getCreatedAt()).as("塞回队尾要刷新排序时间").isGreaterThan(3_000L);

        // 队列里仍只有它一条未结束，位置回到队首
        List<Intent> queue = intentMapper.selectQueue(productId);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).getId()).isEqualTo(intentC);
    }

    @Test
    @Order(9)
    void 队列页只展示未结束的意向() throws Exception {
        String html = mockMvc.perform(asSeller(get("/admin/intents/" + productId)))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/seller-intents"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 买家丙还在排队，甲和乙已结束
        assertThat(html).contains("买家丙");
        assertThat(html).doesNotContain("买家甲");
        assertThat(html).doesNotContain("买家乙");
    }
}

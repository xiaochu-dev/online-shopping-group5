package com.group5.shop;

import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.entity.Category;
import com.group5.shop.common.entity.Intent;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.mapper.CategoryMapper;
import com.group5.shop.common.mapper.IntentMapper;
import com.group5.shop.common.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * @author 周到
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBuyerContext.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyOrdersFlowTest {

    private static final Path TEST_DB =
            Path.of("target", "test-orders-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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

    /** 较早成交的商品 */
    private static Long earlyProductId;
    /** 较晚成交的商品 */
    private static Long lateProductId;
    /** 仍在排队、不该出现在历史里的商品 */
    private static Long pendingProductId;

    @BeforeEach
    void resetLoginState() {
        TestBuyerContext.reset();
    }

    @BeforeAll
    void seed() {
        List<Category> categories = categoryMapper.selectEnabled();
        Long categoryId = categories.get(0).getId();

        earlyProductId = insertProduct("成交较早的台灯", categoryId);
        lateProductId = insertProduct("成交较晚的椅子", categoryId);
        pendingProductId = insertProduct("还在排队的加湿器", categoryId);

        // 失败的早、成功的晚：用来验证「按交易时间倒序」而不是按主键顺/逆序
        insertIntent(earlyProductId, IntentStatus.FAILED, 1_700_000_000_000L);
        insertIntent(lateProductId, IntentStatus.SUCCESS, 1_700_000_900_000L);

        // 排队中的单：不应进入历史列表
        insertIntent(pendingProductId, IntentStatus.PENDING, null);
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

    private void insertIntent(Long productId, String status, Long finishedAt) {
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(TestBuyerContext.BUYER_ID);
        intent.setBuyerName("测试客户");
        intent.setPhone("13800000000");
        intent.setTradeAddress("图书馆门口");
        intent.setStatus(status);
        intent.setCreatedAt(1_600_000_000_000L);
        intent.setFinishedAt(finishedAt);
        if (IntentStatus.SUCCESS.equals(status) || IntentStatus.FAILED.equals(status)) {
            intent.setTradeResult(status);
        }
        intentMapper.insert(intent);
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 未登录访问我的订单跳买家登录页() throws Exception {
        TestBuyerContext.currentId = null;

        mockMvc.perform(get("/my/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));
    }

    @Test
    void 只显示已有结果的订单且按交易时间倒序() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/my/orders"))
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
        TestBuyerContext.currentId = TestBuyerContext.OTHER_BUYER_ID;

        String html = bodyOf(mockMvc.perform(get("/my/orders"))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("还没有成交记录");
        assertThat(html).doesNotContain("成交较早的台灯");
    }
}

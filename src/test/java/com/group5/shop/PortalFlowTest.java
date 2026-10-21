package com.group5.shop;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * T2-13 ~ T2-16 验收测试：买家门户的四条链路。
 *
 * <p>覆盖：列表（分页 / 一级分类筛选 / 关键词搜索）、详情（多图与状态）、
 * <b>下单强制登录</b>、买家资料快照、重复提交限制，以及「下架商品不可见」这类
 * 只能靠走完整 DispatcherServlet 链路验证的入口规则。</p>
 *
 * <p>买家身份用 {@code @MockBean} 顶替：登录态归账号与客户模块，门户只消费
 * {@code BuyerContext.current()} 的结果。这里验证的是「拿到身份之后门户做的事」。</p>
 *
 * @author 周到
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PortalFlowTest {

    /** 本测试类专用数据库，路径带时间戳，避免多次运行互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-portal-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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

    @MockBean
    private BuyerContext buyerContext;

    private static final Long BUYER_ID = 9001L;
    private static final Long OTHER_BUYER_ID = 9002L;

    /** 在售商品：数码电子 */
    private static Long onSaleId;
    /** 在售商品：图书教材 */
    private static Long textbookId;
    /** 已下架商品 */
    private static Long delistedId;
    /** 交易中（冻结）商品 */
    private static Long frozenId;
    /** 图书分类 id，分类筛选用 */
    private static Long bookCategoryId;

    @BeforeEach
    void resetLoginState() {
        when(buyerContext.current()).thenReturn(buyer(BUYER_ID, "测试客户", "13800000000", "图书馆门口"));
    }

    /**
     * 造测试数据：两个不同分类的在售商品 + 一个已下架 + 一个交易中。
     *
     * <p>种子账号与 5 个分类由 {@code schema/*.sql} 在上下文启动时写入，这里只补商品。</p>
     */
    @BeforeAll
    void seed() {
        List<Category> categories = categoryMapper.selectEnabled();
        assertThat(categories).as("schema 应已写入种子分类").hasSizeGreaterThanOrEqualTo(2);
        Long digitalId = categories.get(0).getId();
        bookCategoryId = categories.get(1).getId();

        onSaleId = insertProduct("机械键盘 87 键", "青轴，白色背光", digitalId, 29900L, ProductStatus.ON_SALE);
        textbookId = insertProduct("软件工程实践教材", "2026 版，含实验指导", bookCategoryId, 4550L, ProductStatus.ON_SALE);
        delistedId = insertProduct("已下架的旧款鼠标", "这款应当对买家不可见", digitalId, 9900L, ProductStatus.DELISTED);
        frozenId = insertProduct("正在交易的显示器", "有人正在与卖家成交", digitalId, 89900L, ProductStatus.FROZEN);
    }

    private Buyer buyer(Long id, String username, String phone, String place) {
        Buyer buyer = new Buyer();
        buyer.setId(id);
        buyer.setUsername(username);
        buyer.setPhone(phone);
        buyer.setDefaultPlace(place);
        return buyer;
    }

    private Long insertProduct(String name, String desc, Long categoryId, Long priceCents, String status) {
        Product p = new Product();
        p.setSellerId(1L);
        p.setCategoryId(categoryId);
        p.setName(name);
        p.setDescription(desc);
        p.setImagePath("images/placeholder.png");
        p.setPriceCents(priceCents);
        p.setStatus(status);
        long now = System.currentTimeMillis();
        p.setPublishedAt(now);
        p.setUpdatedAt(now);
        productMapper.insert(p);
        return p.getId();
    }

    /** 把响应正文按 UTF-8 取出来（默认按 ISO-8859-1 读，中文会乱码） */
    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 列表 / 搜索 / 分类筛选（T2-13 / T2-14）
    // ==================================================================

    @Test
    @Order(1)
    void 买家首页公开可访问且只显示在售商品() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-list"))
                .andExpect(model().attributeExists("pageResult", "categoryTree", "categoryNames"))
                .andReturn());

        assertThat(html).contains("机械键盘 87 键");
        assertThat(html).contains("软件工程实践教材");
        // 已下架与交易中的商品都不该出现在列表里（列表只查 on_sale）
        assertThat(html).doesNotContain("已下架的旧款鼠标");
        assertThat(html).doesNotContain("正在交易的显示器");
    }

    @Test
    @Order(2)
    void 关键词搜索命中名称也命中描述() throws Exception {
        String byName = bodyOf(mockMvc.perform(get("/").param("keyword", "键盘"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byName).contains("机械键盘 87 键");

        String byDesc = bodyOf(mockMvc.perform(get("/").param("keyword", "实验指导"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byDesc).contains("软件工程实践教材");
    }

    @Test
    @Order(3)
    void 关键词无匹配时显示空态提示() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/").param("keyword", "不存在的商品名"))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("没有找到符合条件的商品");
    }

    @Test
    @Order(4)
    void 分类筛选只返回该分类商品() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/").param("categoryId", String.valueOf(bookCategoryId)))
                .andExpect(status().isOk()).andReturn());

        assertThat(html).contains("软件工程实践教材");
        assertThat(html).doesNotContain("机械键盘 87 键");

        // 传一个不存在的分类：应给空结果，而不是「筛选失效返回全部」
        String unknown = bodyOf(mockMvc.perform(get("/").param("categoryId", "999999"))
                .andExpect(status().isOk()).andReturn());
        assertThat(unknown).contains("没有找到符合条件的商品");
    }

    @Test
    @Order(5)
    void 分页参数非法时回落到默认值而不报错() throws Exception {
        mockMvc.perform(get("/").param("page", "0").param("size", "-5"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("pageResult"));
    }

    // ==================================================================
    // 详情（T2-15）
    // ==================================================================

    @Test
    @Order(6)
    void 在售商品详情可访问且显示排队人数() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/products/" + onSaleId))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-detail"))
                .andExpect(model().attributeExists("view", "product", "queueSize"))
                .andReturn());

        assertThat(html).contains("机械键盘 87 键");
        assertThat(html).contains("人在排队");
    }

    @Test
    @Order(7)
    void 已下架与不存在的商品详情返回404() throws Exception {
        mockMvc.perform(get("/products/" + delistedId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/products/99999999")).andExpect(status().isNotFound());
    }

    @Test
    @Order(8)
    void 交易中的商品详情可见但下单按钮不可用() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/products/" + frozenId))
                .andExpect(status().isOk()).andReturn());

        // 交易中的商品不能 404——买家要能看到「还在，只是正在成交」，否则以为商品没了
        assertThat(html).contains("正在交易的显示器");
        assertThat(html).contains("该商品正在交易中，暂时无法提交意向");
        assertThat(html).doesNotContain("/intents\"");
    }

    // ==================================================================
    // 下单：强制登录 + 快照 + 去重（T2-16）
    // ==================================================================

    @Test
    @Order(9)
    void 未登录提交意向跳买家登录页并带回跳地址() throws Exception {
        when(buyerContext.current()).thenReturn(null);

        mockMvc.perform(post("/products/" + onSaleId + "/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));
    }

    @Test
    @Order(10)
    void 已登录下单成功并落库买家快照() throws Exception {
        mockMvc.perform(post("/products/" + onSaleId + "/intents")
                        .param("tradeAddress", "三食堂门口"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/products/" + onSaleId + "?msg=*"));

        Intent saved = intentMapper.selectActiveQueue(onSaleId).stream()
                .filter(i -> BUYER_ID.equals(i.getBuyerId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("下单应写入一条 pending 意向"));

        assertThat(saved.getStatus()).isEqualTo(IntentStatus.PENDING);
        assertThat(saved.getBuyerName()).isEqualTo("测试客户");
        assertThat(saved.getPhone()).isEqualTo("13800000000");
        assertThat(saved.getTradeAddress()).isEqualTo("三食堂门口");
        // 主路径不再生成口令码
        assertThat(saved.getTokenCode()).isNull();
        assertThat(saved.getFinishedAt()).isNull();
    }

    @Test
    @Order(11)
    void 同一买家对同一商品重复提交被拒() throws Exception {
        mockMvc.perform(post("/products/" + onSaleId + "/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/products/" + onSaleId + "?error=*"));

        long active = intentMapper.selectActiveQueue(onSaleId).stream()
                .filter(i -> BUYER_ID.equals(i.getBuyerId()))
                .count();
        assertThat(active).as("同一买家同一商品最多 1 条未结束意向").isEqualTo(1);
    }

    @Test
    @Order(12)
    void 未填写交易地点时使用注册时的默认地点() throws Exception {
        when(buyerContext.current())
                .thenReturn(buyer(OTHER_BUYER_ID, "另一位客户", "13900000000", "东门快递点"));

        mockMvc.perform(post("/products/" + textbookId + "/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/products/" + textbookId + "?msg=*"));

        Intent saved = intentMapper.selectActiveQueue(textbookId).stream()
                .filter(i -> OTHER_BUYER_ID.equals(i.getBuyerId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("下单应写入一条 pending 意向"));

        assertThat(saved.getTradeAddress()).isEqualTo("东门快递点");
    }

    @Test
    @Order(13)
    void 已下架商品不接受新意向() throws Exception {
        mockMvc.perform(post("/products/" + delistedId + "/intents"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/products/" + delistedId + "?error=*"));
    }
}

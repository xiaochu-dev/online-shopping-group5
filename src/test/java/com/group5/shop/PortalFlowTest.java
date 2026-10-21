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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * T2-11 ~ T2-14 验收测试：买家门户的四条链路。
 *
 * <p>覆盖范围与 {@code docs/API契约-迭代2.md} 第 4 节的 B-1 ~ B-4 一一对应：
 * 列表（分页/分类/搜索）、详情、提交意向、凭口令码撤销，
 * 外加三条「不能只靠肉眼确认」的规则：下架商品不可见、同号可重复提交、撤销提示不泄露码是否有效。</p>
 *
 * <p><b>为什么用 MockMvc 而不是直接调 Service</b>：本模块最容易错的地方在「入口」——
 * 买家页必须公开（不能被登录拦截器拦成 302）、商品不存在要走错误页（404 而非 500）、
 * 提交成功后口令码只出现在响应正文里（不能进 URL）。这些只有走完整 DispatcherServlet 链路才验证得了。</p>
 *
 * <p>每个测试类使用独立临时数据库，互不干扰；数据由 {@link #seed()} 一次性造好。</p>
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

    /** 在售商品：数码电子（分类 1） */
    private static Long onSaleId;
    /** 在售商品：图书教材（分类 2） */
    private static Long textbookId;
    /** 已下架商品 */
    private static Long delistedId;
    /** 提交意向后拿到的口令码，供后续撤销用例复用 */
    private static String tokenOfBuyerA;

    /**
     * 造测试数据：两个不同分类的在售商品 + 一个已下架商品。
     *
     * <p>种子账号与 5 个分类由 {@code schema.sql} 在上下文启动时写入，这里只补商品。</p>
     */
    @BeforeAll
    void seed() {
        List<Category> categories = categoryMapper.selectEnabled();
        assertThat(categories).as("schema.sql 应已写入 5 个种子分类").hasSizeGreaterThanOrEqualTo(2);
        Long digitalId = categories.get(0).getId();
        Long bookId = categories.get(1).getId();

        onSaleId = insertProduct("机械键盘 87 键", "青轴，白色背光", digitalId, 29900L, ProductStatus.ON_SALE);
        textbookId = insertProduct("软件工程实践教材", "2026 版，含实验指导", bookId, 4550L, ProductStatus.ON_SALE);
        delistedId = insertProduct("已下架的旧款鼠标", "这款应当对买家不可见", digitalId, 9900L, ProductStatus.DELISTED);
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
    // B-1 列表 / T2-12 搜索与分类筛选
    // ==================================================================

    @Test
    @Order(1)
    void 买家首页公开可访问且只显示在售商品() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-list"))
                .andReturn();

        String html = bodyOf(result);
        assertThat(html).contains("机械键盘 87 键", "软件工程实践教材");
        // 下架商品不能出现在买家列表里（FR-03-1）
        assertThat(html).doesNotContain("已下架的旧款鼠标");
        // 价格必须以「元、两位小数」展示，不能是 299 或 29900
        assertThat(html).contains("¥ 299.00", "¥ 45.50").doesNotContain("¥ 29900");
    }

    @Test
    @Order(2)
    void 关键词搜索命中名称也命中描述() throws Exception {
        // 命中名称
        String byName = bodyOf(mockMvc.perform(get("/").param("keyword", "键盘"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byName).contains("机械键盘 87 键").doesNotContain("软件工程实践教材");

        // 命中描述（「含实验指导」只出现在描述里）
        String byDesc = bodyOf(mockMvc.perform(get("/").param("keyword", "实验指导"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byDesc).contains("软件工程实践教材").doesNotContain("机械键盘 87 键");
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
        Long bookCategoryId = productMapper.selectById(textbookId).getCategoryId();
        String html = bodyOf(mockMvc.perform(get("/").param("categoryId", String.valueOf(bookCategoryId)))
                .andExpect(status().isOk()).andReturn());
        assertThat(html).contains("软件工程实践教材").doesNotContain("机械键盘 87 键");
    }

    @Test
    @Order(5)
    void 分页参数非法时回落到默认值而不报错() throws Exception {
        // page=0 与 size=-5 都属于「非法输入」，应归一化后正常返回第一页
        mockMvc.perform(get("/").param("page", "0").param("size", "-5"))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-list"));
    }

    // ==================================================================
    // B-2 详情
    // ==================================================================

    @Test
    @Order(6)
    void 在售商品详情可访问且显示排队人数() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/products/" + onSaleId))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-detail"))
                .andExpect(model().attributeExists("product", "queueSize"))
                .andReturn());
        assertThat(html).contains("机械键盘 87 键", "提交购买意向");
    }

    @Test
    @Order(7)
    void 已下架商品详情返回404() throws Exception {
        // 不能是 500，也不能让买家看到「已下架商品」的详情
        mockMvc.perform(get("/products/" + delistedId)).andExpect(status().isNotFound());
    }

    @Test
    @Order(8)
    void 不存在的商品详情返回404() throws Exception {
        mockMvc.perform(get("/products/99999999")).andExpect(status().isNotFound());
    }

    // ==================================================================
    // B-3 提交意向
    // ==================================================================

    @Test
    @Order(9)
    void 提交意向成功并当场返回32位口令码() throws Exception {
        long before = intentMapper.selectQueue(onSaleId).size();

        MvcResult result = mockMvc.perform(post("/products/" + onSaleId + "/intents")
                        .param("buyerName", "张三")
                        .param("phone", "13800000001"))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-detail"))
                .andReturn();

        tokenOfBuyerA = (String) result.getModelAndView().getModel().get("tokenCode");
        assertThat(tokenOfBuyerA)
                .as("口令码应为 32 位十六进制")
                .hasSize(32).matches("[0-9a-f]{32}");
        // 口令码不能出现在 URL 上（否则进浏览器历史与访问日志）
        assertThat(result.getResponse().getRedirectedUrl()).isNull();

        Intent saved = intentMapper.selectByTokenCode(tokenOfBuyerA);
        assertThat(saved).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(IntentStatus.PENDING);
        assertThat(saved.getBuyerName()).isEqualTo("张三");
        assertThat(intentMapper.selectQueue(onSaleId)).hasSize((int) before + 1);
    }

    @Test
    @Order(10)
    void 姓名为空白时拒绝提交且不写库() throws Exception {
        long before = intentMapper.selectQueue(onSaleId).size();

        MvcResult result = mockMvc.perform(post("/products/" + onSaleId + "/intents")
                        .param("buyerName", "   ")
                        .param("phone", "13800000002"))
                .andExpect(status().isOk())
                .andExpect(view().name("portal/product-detail"))
                .andReturn();

        assertThat(bodyOf(result)).contains("姓名与联系电话均为必填项");
        assertThat(intentMapper.selectQueue(onSaleId)).as("校验失败不能留下脏数据").hasSize((int) before);
    }

    @Test
    @Order(11)
    void 同一手机号连续提交两次得到两条独立记录() throws Exception {
        // 清偿迭代 1 遗留 C-3：不按号码去重
        MvcResult first = mockMvc.perform(post("/products/" + textbookId + "/intents")
                        .param("buyerName", "李四").param("phone", "13900000001"))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/products/" + textbookId + "/intents")
                        .param("buyerName", "李四").param("phone", "13900000001"))
                .andExpect(status().isOk()).andReturn();

        String t1 = (String) first.getModelAndView().getModel().get("tokenCode");
        String t2 = (String) second.getModelAndView().getModel().get("tokenCode");
        assertThat(t1).isNotEqualTo(t2);
        assertThat(intentMapper.selectQueue(textbookId)).hasSize(2);
    }

    @Test
    @Order(12)
    void 已下架商品不接受新意向() throws Exception {
        long before = intentMapper.selectQueue(delistedId).size();

        mockMvc.perform(post("/products/" + delistedId + "/intents")
                        .param("buyerName", "王五").param("phone", "13700000001"))
                .andExpect(status().isNotFound());

        assertThat(intentMapper.selectQueue(delistedId)).hasSize((int) before);
    }

    // ==================================================================
    // B-4 撤销
    // ==================================================================

    @Test
    @Order(13)
    void 撤销排队中的意向成功且状态变为已撤销() throws Exception {
        long before = intentMapper.selectQueue(onSaleId).size();

        String redirected = mockMvc.perform(post("/intents/cancel").param("tokenCode", tokenOfBuyerA))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirected).contains("/?msg=");
        assertThat(intentMapper.selectByTokenCode(tokenOfBuyerA).getStatus())
                .isEqualTo(IntentStatus.CANCELLED);
        assertThat(intentMapper.selectQueue(onSaleId))
                .as("撤销后队列自动少一条，其余买家前移")
                .hasSize((int) before - 1);
    }

    @Test
    @Order(14)
    void 重复撤销同一口令码的提示与码无效时完全一致() throws Exception {
        String again = mockMvc.perform(post("/intents/cancel").param("tokenCode", tokenOfBuyerA))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String never = mockMvc.perform(post("/intents/cancel").param("tokenCode", "deadbeef".repeat(4)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        // 提示语必须一字不差，否则等于告诉对方「这个码是真的，只是用过了」
        assertThat(again).contains("/?error=").isEqualTo(never);
    }

    @Test
    @Order(15)
    void 空口令码按无效处理不报异常() throws Exception {
        mockMvc.perform(post("/intents/cancel").param("tokenCode", ""))
                .andExpect(status().is3xxRedirection());
    }

    // ==================================================================
    // 队列语义
    // ==================================================================

    @Test
    @Order(16)
    void 队列严格按提交时间先到先得() throws Exception {
        Long productId = insertProduct("队列排序测试品", "用来验证先到先得", null, 100L, ProductStatus.ON_SALE);
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/products/" + productId + "/intents")
                            .param("buyerName", "买家" + i).param("phone", "1360000000" + i))
                    .andExpect(status().isOk());
        }

        List<Intent> queue = intentMapper.selectQueue(productId);
        assertThat(queue).hasSize(3);
        assertThat(queue).extracting(Intent::getBuyerName).containsExactly("买家1", "买家2", "买家3");
        // created_at 单调不减，且同一毫秒时由自增 id 兜底
        for (int i = 1; i < queue.size(); i++) {
            Intent prev = queue.get(i - 1);
            Intent cur = queue.get(i);
            assertThat(prev.getCreatedAt() < cur.getCreatedAt()
                    || (prev.getCreatedAt().equals(cur.getCreatedAt()) && prev.getId() < cur.getId()))
                    .as("第 %d 位与第 %d 位顺序必须稳定", i, i + 1)
                    .isTrue();
        }
    }
}

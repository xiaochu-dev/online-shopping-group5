package com.group5.shop;

import com.group5.shop.buyer.context.BuyerContext;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.payment.constant.PaymentStatus;
import com.group5.shop.payment.domain.Payment;
import com.group5.shop.payment.domain.PaymentMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 模拟支付模块验收测试：支付状态机主流程。
 *
 * <p>覆盖一条完整链路：
 * <ol>
 *   <li><b>生成支付单</b>：打开支付页自动创建 pending 支付单，金额快照自商品价格（分）；</li>
 *   <li><b>模拟支付成功</b>：支付单 paid、订单自动 success、扣 1 件库存；</li>
 *   <li><b>订单完成</b>：{@code intent.tradeResult = success}、{@code finishedAt} 落库；</li>
 *   <li><b>退款</b>：支付单 refunded、订单回退 failed（原因「已退款」）。</li>
 * </ol>
 * 另覆盖三条易错点：重复支付被拒、重复退款被拒、越权支付被拒。</p>
 *
 * <p>买家身份用 {@code @MockBean} 顶替：登录态归账号模块，支付模块只消费
 * {@code BuyerContext.current()} 的结果；服务层归属校验则用「支付单/订单的 buyerId
 * 必须等于当前买家」来验证越权拦截。</p>
 *
 * @author 第 5 组（模拟支付模块）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentTest {

    /** 本测试类专用数据库，路径带时间戳，避免多次运行互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-payment-" + System.currentTimeMillis() + ".db").toAbsolutePath();

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
    private PaymentMapper paymentMapper;

    @MockBean
    private BuyerContext buyerContext;

    private static final Long BUYER_ID = 9001L;
    private static final Long OTHER_BUYER_ID = 9002L;

    /** 主链路用的商品与意向 */
    private static Long productId;
    private static Long intentId;

    private Buyer buyer(Long id, String username) {
        Buyer b = new Buyer();
        b.setId(id);
        b.setUsername(username);
        b.setPhone("13800000000");
        b.setDefaultPlace("图书馆门口");
        return b;
    }

    @BeforeEach
    void resetLoginState() {
        when(buyerContext.current()).thenReturn(buyer(BUYER_ID, "测试买家"));
    }

    /**
     * 造测试数据：一个在售商品（库存 3、价格 899.00 元）+ 一条 pending 意向。
     *
     * <p>种子分类由 {@code schema/*.sql} 在上下文启动时写入，这里只补商品与意向。</p>
     */
    @BeforeAll
    void seed() {
        productId = seedProduct("线上支付的二手显示器", 89900L, 3);
        intentId = seedIntent(productId, BUYER_ID);
    }

    /** 造一个在售商品，返回商品 id */
    private Long seedProduct(String name, long priceCents, int stock) {
        List<Category> categories = categoryMapper.selectEnabled();
        Product p = new Product();
        p.setSellerId(1L);
        p.setCategoryId(categories.get(0).getId());
        p.setName(name);
        p.setStock(stock);
        p.setDescription("测试用商品");
        p.setImagePath("images/placeholder.png");
        p.setPriceCents(priceCents);
        p.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        p.setPublishedAt(now);
        p.setUpdatedAt(now);
        productMapper.insert(p);
        return p.getId();
    }

    /** 造一条 pending 意向，返回意向 id */
    private Long seedIntent(Long productId, Long buyerId) {
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerId(buyerId);
        intent.setBuyerName("测试买家");
        intent.setPhone("13800000000");
        intent.setTradeAddress("图书馆门口");
        intent.setStatus(IntentStatus.PENDING);
        intent.setCreatedAt(System.currentTimeMillis());
        intentMapper.insert(intent);
        return intent.getId();
    }

    /** 把响应正文按 UTF-8 取出来（默认按 ISO-8859-1 读，中文会乱码） */
    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 权限
    // ==================================================================

    @Test
    @Order(1)
    void 未登录访问支付页跳买家登录页() throws Exception {
        when(buyerContext.current()).thenReturn(null);

        mockMvc.perform(get("/pay/" + intentId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));
    }

    // ==================================================================
    // 一、生成支付单
    // ==================================================================

    @Test
    @Order(2)
    void 打开支付页自动生成待支付支付单() throws Exception {
        String html = bodyOf(mockMvc.perform(get("/pay/" + intentId))
                .andExpect(status().isOk())
                .andExpect(view().name("payment/payment"))
                .andReturn());

        // 页面展示金额（89900 分 = 899.00 元）与「待支付」状态
        assertThat(html).contains("899.00");
        assertThat(html).contains("待支付");
        assertThat(html).contains("模拟支付");

        Payment payment = paymentMapper.selectByIntentId(intentId);
        assertThat(payment).as("打开支付页应自动生成支付单").isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmountCents()).as("金额应快照自商品价格（分）").isEqualTo(89900L);
        assertThat(payment.getBuyerId()).isEqualTo(BUYER_ID);
        assertThat(payment.getTradeNo()).as("支付流水号必须生成").isNotBlank();
        assertThat(payment.getPaidAt()).as("未支付时支付时间为空").isNull();
    }

    @Test
    @Order(3)
    void 重复打开支付页不生成第二张支付单() throws Exception {
        mockMvc.perform(get("/pay/" + intentId)).andExpect(status().isOk());
        assertThat(paymentMapper.selectCount(null)).as("一张意向最多一张支付单").isEqualTo(1L);
    }

    // ==================================================================
    // 二、模拟支付成功 → 订单完成
    // ==================================================================

    @Test
    @Order(4)
    void 模拟支付成功后支付单已支付且订单自动完成() throws Exception {
        mockMvc.perform(post("/pay/" + intentId + "/pay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pay/" + intentId + "?msg=*"));

        Payment payment = paymentMapper.selectByIntentId(intentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).as("支付成功必须落支付时间").isNotNull();

        Intent intent = intentMapper.selectById(intentId);
        assertThat(intent.getStatus()).as("支付成功订单应自动完成").isEqualTo(IntentStatus.SUCCESS);
        assertThat(intent.getTradeResult()).isEqualTo(IntentStatus.SUCCESS);
        assertThat(intent.getFinishedAt()).as("交易时间必须落库").isNotNull();

        assertThat(productMapper.selectById(productId).getStock())
                .as("支付成功真实扣减 1 件库存：3 → 2").isEqualTo(2);
    }

    @Test
    @Order(5)
    void 已支付的单不能重复支付() throws Exception {
        mockMvc.perform(post("/pay/" + intentId + "/pay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pay/" + intentId + "?error=*"));

        assertThat(paymentMapper.selectByIntentId(intentId).getStatus())
                .as("非法流转（重复支付）不得改变状态").isEqualTo(PaymentStatus.PAID);
    }

    // ==================================================================
    // 三、退款
    // ==================================================================

    @Test
    @Order(6)
    void 申请退款后支付单已退款且订单回退为失败() throws Exception {
        mockMvc.perform(post("/pay/" + intentId + "/refund"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pay/" + intentId + "?msg=*"));

        Payment payment = paymentMapper.selectByIntentId(intentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).as("退款成功必须落退款时间").isNotNull();

        Intent intent = intentMapper.selectById(intentId);
        assertThat(intent.getStatus()).as("退款后订单回退为失败").isEqualTo(IntentStatus.FAILED);
        assertThat(intent.getTradeResult()).isEqualTo(IntentStatus.FAILED);
        assertThat(intent.getFailReason()).isEqualTo("已退款");
    }

    @Test
    @Order(7)
    void 已退款的单不能再次退款() throws Exception {
        mockMvc.perform(post("/pay/" + intentId + "/refund"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pay/" + intentId + "?error=*"));

        assertThat(paymentMapper.selectByIntentId(intentId).getStatus())
                .as("非法流转（重复退款）不得改变状态").isEqualTo(PaymentStatus.REFUNDED);
    }

    // ==================================================================
    // 四、越权
    // ==================================================================

    @Test
    @Order(8)
    void 他人不能支付不属于自己的订单() throws Exception {
        // 造一笔属于买家 9001 的全新待支付订单（独立商品，避开主链路数据）
        Long otherProductId = seedProduct("越权测试商品", 9900L, 1);
        Long otherIntentId = seedIntent(otherProductId, BUYER_ID);
        mockMvc.perform(get("/pay/" + otherIntentId)).andExpect(status().isOk());
        assertThat(paymentMapper.selectByIntentId(otherIntentId).getStatus())
                .isEqualTo(PaymentStatus.PENDING);

        // 切换成买家 9002 尝试支付
        when(buyerContext.current()).thenReturn(buyer(OTHER_BUYER_ID, "越权买家"));
        mockMvc.perform(post("/pay/" + otherIntentId + "/pay"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/pay/" + otherIntentId + "?error=*"));

        assertThat(paymentMapper.selectByIntentId(otherIntentId).getStatus())
                .as("越权支付不得改变支付单状态").isEqualTo(PaymentStatus.PENDING);
        assertThat(intentMapper.selectById(otherIntentId).getStatus())
                .as("越权支付不得改变订单状态").isEqualTo(IntentStatus.PENDING);
    }
}

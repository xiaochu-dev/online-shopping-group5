package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import com.group5.shop.shipping.domain.BuyerAddress;
import com.group5.shop.shipping.domain.BuyerAddressMapper;
import com.group5.shop.shipping.domain.Shipment;
import com.group5.shop.shipping.domain.ShipmentMapper;
import com.group5.shop.shipping.service.TrackingService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配送升级的接口契约测试（挑战需求 C.2.1 ~ C.2.4，owner：胡成溯）。
 *
 * <p><b>关注四件事</b>：地址簿的「默认唯一 + 同步下单地点」、越权拦截、发货推进与
 * 重复发货防线、物流轨迹的时间推算。</p>
 *
 * <p><b>订单数据直接落库构造</b>：本类要测的是「配送模块对订单状态的反应」，
 * 不是下单链路本身（那由订单域的测试覆盖）。用 SQL 直接造一张「备货完成」的单，
 * 可以让配送测试不受下单流程改动的影响。</p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShippingTest {

    /** 本测试类专用数据库，避免与其他测试类互相污染 */
    private static final Path TEST_DB =
            Path.of("target", "test-shipping-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BuyerMapper buyerMapper;
    @Autowired
    private BuyerAddressMapper addressMapper;
    @Autowired
    private ShipmentMapper shipmentMapper;
    @Autowired
    private TrackingService trackingService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    private static final String PASSWORD = "Passw0rd2026";
    private static final String BUYER_A = "ship_buyer_a";
    private static final String BUYER_B = "ship_buyer_b";
    private static final String SELLER_USERNAME = "admin";
    private static final String SELLER_PASSWORD = "admin12345";

    private static Long buyerAId;
    private static Long buyerBId;
    private static Long productId;
    /** 备货完成、待发货的订单 */
    private static Long preparedOrderId;
    /** 刚下单、还不能发货的订单 */
    private static Long createdOrderId;

    @BeforeAll
    void seed() throws Exception {
        List<Category> categories = categoryMapper.selectEnabled();
        assertThat(categories).as("schema 应已写入种子分类").isNotEmpty();
        productId = insertProduct("配送测试商品", categories.get(0).getId());

        buyerAId = register(BUYER_A, "13900003333");
        buyerBId = register(BUYER_B, "13900004444");

        preparedOrderId = insertOrder(buyerAId, "prepared", "SO20261120000001");
        createdOrderId = insertOrder(buyerAId, "created", "SO20261120000002");
    }

    // ==================================================================
    // 1. 登录门槛
    // ==================================================================

    @Test
    @Order(1)
    @DisplayName("未登录访问收货地址：302 到买家登录页并带回跳地址")
    void 未登录访问地址簿被拦截() throws Exception {
        mockMvc.perform(get("/my/addresses"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Faddresses"));
    }

    // ==================================================================
    // 2. 地址簿：默认唯一 + 同步下单地点
    // ==================================================================

    @Test
    @Order(2)
    @DisplayName("新增首条地址：自动成为默认，并同步到下单用的交易地点")
    void 首条地址自动设为默认并同步下单地点() throws Exception {
        mockMvc.perform(post("/my/addresses/add").cookie(loginCookie(BUYER_A))
                        .param("receiver", "胡成溯")
                        .param("phone", "13900003333")
                        .param("region", "浙江省杭州市西湖区")
                        .param("detail", "文一西路 18 号"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/addresses?msg=*"));

        List<BuyerAddress> list = addressMapper.selectByBuyer(buyerAId);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).defaulted()).as("首条地址必须自动成为默认").isTrue();
        assertThat(buyerMapper.selectById(buyerAId).getDefaultPlace())
                .as("默认地址要同步到 buyer.default_place，下单链路才能直接用")
                .isEqualTo("浙江省杭州市西湖区文一西路 18 号");
    }

    @Test
    @Order(3)
    @DisplayName("新增第二条地址：不改变默认地址与下单地点")
    void 第二条地址不抢默认() throws Exception {
        mockMvc.perform(post("/my/addresses/add").cookie(loginCookie(BUYER_A))
                        .param("receiver", "胡爸爸")
                        .param("phone", "13900005555")
                        .param("region", "浙江省宁波市海曙区")
                        .param("detail", "中山路 1 号"))
                .andExpect(status().isFound());

        List<BuyerAddress> list = addressMapper.selectByBuyer(buyerAId);
        assertThat(list).hasSize(2);
        assertThat(list.stream().filter(BuyerAddress::defaulted).count()).as("默认地址只能有一个").isEqualTo(1);
        assertThat(list.get(0).getReceiver()).as("默认地址排最前").isEqualTo("胡成溯");
        assertThat(buyerMapper.selectById(buyerAId).getDefaultPlace())
                .as("新增普通地址不应改变下单地点").isEqualTo("浙江省杭州市西湖区文一西路 18 号");
    }

    @Test
    @Order(4)
    @DisplayName("设为默认：切换唯一默认，并同步下单地点")
    void 切换默认地址同步下单地点() throws Exception {
        Long second = addressMapper.selectByBuyer(buyerAId).stream()
                .filter(a -> "胡爸爸".equals(a.getReceiver())).findFirst().orElseThrow().getId();

        mockMvc.perform(post("/my/addresses/" + second + "/default").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/addresses?msg=*"));

        assertThat(addressMapper.selectDefault(buyerAId).getReceiver()).isEqualTo("胡爸爸");
        assertThat(buyerMapper.selectById(buyerAId).getDefaultPlace())
                .isEqualTo("浙江省宁波市海曙区中山路 1 号");
    }

    @Test
    @Order(5)
    @DisplayName("修改默认地址内容：下单地点跟着更新")
    void 修改默认地址同步下单地点() throws Exception {
        BuyerAddress current = addressMapper.selectDefault(buyerAId);

        mockMvc.perform(post("/my/addresses/" + current.getId() + "/update").cookie(loginCookie(BUYER_A))
                        .param("receiver", "胡爸爸")
                        .param("phone", "13900005555")
                        .param("region", "浙江省宁波市海曙区")
                        .param("detail", "中山路 2 号"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/addresses?msg=*"));

        assertThat(buyerMapper.selectById(buyerAId).getDefaultPlace())
                .as("默认地址内容变了，下单地点必须同步，否则用户下单还是老地址")
                .isEqualTo("浙江省宁波市海曙区中山路 2 号");
    }

    @Test
    @Order(6)
    @DisplayName("删除默认地址：自动递补一条为默认，不出现「有地址但无默认」")
    void 删除默认地址自动递补() throws Exception {
        BuyerAddress current = addressMapper.selectDefault(buyerAId);

        mockMvc.perform(post("/my/addresses/" + current.getId() + "/delete").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isFound());

        List<BuyerAddress> rest = addressMapper.selectByBuyer(buyerAId);
        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).defaulted()).as("剩下那条必须递补为默认").isTrue();
        assertThat(buyerMapper.selectById(buyerAId).getDefaultPlace()).isEqualTo(rest.get(0).fullAddress());
    }

    @Test
    @Order(7)
    @DisplayName("越权改别人的地址：报地址不存在，且目标数据不变")
    void 不能修改别人的地址() throws Exception {
        BuyerAddress target = addressMapper.selectDefault(buyerAId);

        mockMvc.perform(post("/my/addresses/" + target.getId() + "/update").cookie(loginCookie(BUYER_B))
                        .param("receiver", "入侵者")
                        .param("phone", "13900009999")
                        .param("region", "某省某市")
                        .param("detail", "某街道"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/addresses?error=*"));

        assertThat(addressMapper.selectById(target.getId()).getReceiver())
                .as("别人的地址不能被改").isEqualTo(target.getReceiver());
    }

    @Test
    @Order(8)
    @DisplayName("必填校验：收货人为空时拒绝保存，不落库")
    void 必填校验拦住空收货人() throws Exception {
        long before = addressMapper.selectByBuyer(buyerBId).size();

        mockMvc.perform(post("/my/addresses/add").cookie(loginCookie(BUYER_B))
                        .param("receiver", "   ")
                        .param("phone", "13900004444")
                        .param("region", "浙江省杭州市")
                        .param("detail", "某路 1 号"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/addresses?error=*"));

        assertThat(addressMapper.selectByBuyer(buyerBId)).hasSize((int) before);
    }

    // ==================================================================
    // 3. 发货：推进状态 + 落库 + 幂等
    // ==================================================================

    @Test
    @Order(9)
    @DisplayName("发货：填承运商与运单号后订单变「已发货」，并生成一条发货记录")
    void 发货推进订单状态并落库() throws Exception {
        assertThat(statusOf(preparedOrderId)).isEqualTo("prepared");

        mockMvc.perform(post("/admin/shipments/" + preparedOrderId + "/ship").session(sellerSession())
                        .param("carrier", "SF")
                        .param("trackingNo", "SF1234567890"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/shipments?msg=*"));

        assertThat(statusOf(preparedOrderId)).as("发货后订单必须进入 shipped").isEqualTo("shipped");
        Shipment shipment = shipmentMapper.selectByIntent(preparedOrderId);
        assertThat(shipment).isNotNull();
        assertThat(shipment.getCarrier()).isEqualTo("SF");
        assertThat(shipment.getTrackingNo()).isEqualTo("SF1234567890");
    }

    @Test
    @Order(10)
    @DisplayName("重复发货：第二次被拒，库里仍只有一条记录")
    void 重复发货被拒() throws Exception {
        mockMvc.perform(post("/admin/shipments/" + preparedOrderId + "/ship").session(sellerSession())
                        .param("carrier", "JD")
                        .param("trackingNo", "JD9999999999"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/shipments?error=*"));

        assertThat(shipmentMapper.selectByIntent(preparedOrderId).getCarrier())
                .as("第二次发货不能覆盖第一次的记录").isEqualTo("SF");
    }

    @Test
    @Order(11)
    @DisplayName("未备货完成的订单不能发货：状态不变、不产生发货记录")
    void 未备货完成不能发货() throws Exception {
        mockMvc.perform(post("/admin/shipments/" + createdOrderId + "/ship").session(sellerSession())
                        .param("carrier", "SF")
                        .param("trackingNo", "SF0000000001"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/shipments?error=*"));

        assertThat(statusOf(createdOrderId)).isEqualTo("created");
        assertThat(shipmentMapper.selectByIntent(createdOrderId)).isNull();
    }

    @Test
    @Order(12)
    @DisplayName("运单号格式非法：被拒，不落库")
    void 运单号格式校验() throws Exception {
        Long another = insertOrder(buyerAId, "prepared", "SO20261120000003");

        mockMvc.perform(post("/admin/shipments/" + another + "/ship").session(sellerSession())
                        .param("carrier", "SF")
                        .param("trackingNo", "中文单号"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/admin/shipments?error=*"));

        assertThat(shipmentMapper.selectByIntent(another)).isNull();
        assertThat(statusOf(another)).isEqualTo("prepared");
    }

    // ==================================================================
    // 4. 物流查询
    // ==================================================================

    @Test
    @Order(13)
    @DisplayName("买家物流列表：只看到自己的已发货订单")
    void 物流列表按买家隔离() throws Exception {
        mockMvc.perform(get("/my/logistics").cookie(loginCookie(BUYER_A)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("顺丰速运")))
                .andExpect(content().string(containsString("SF1234567890")));

        mockMvc.perform(get("/my/logistics").cookie(loginCookie(BUYER_B)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("还没有已发货的订单")));
    }

    @Test
    @Order(14)
    @DisplayName("越权查别人订单的物流：报订单不存在，不泄露轨迹")
    void 不能查别人的物流轨迹() throws Exception {
        mockMvc.perform(get("/my/logistics/" + preparedOrderId).cookie(loginCookie(BUYER_B)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/my/logistics?error=*"));
    }

    @Test
    @Order(15)
    @DisplayName("轨迹按发货时间推算：刚发货只有揽收，24 小时后四个节点全部完成")
    void 轨迹随时间推进() {
        Shipment shipment = shipmentMapper.selectByIntent(preparedOrderId);
        long shippedAt = shipment.getShippedAt();

        var justNow = trackingService.timeline(shipment, shippedAt + 60_000L);
        assertThat(justNow).hasSize(4);
        assertThat(justNow.stream().filter(TrackingService.TrackingEvent::done).count())
                .as("刚发货只应完成「已揽收」").isEqualTo(1);

        var nextDay = trackingService.timeline(shipment, shippedAt + 25 * 3_600_000L);
        assertThat(nextDay.stream().filter(TrackingService.TrackingEvent::done).count())
                .as("24 小时后应全部完成").isEqualTo(4);
        assertThat(trackingService.statusText(shipment, shippedAt + 19 * 3_600_000L)).isEqualTo("派送中");
    }

    // ==================================================================
    // 助手
    // ==================================================================

    private MockHttpSession sellerSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("username", SELLER_USERNAME)
                        .param("password", SELLER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
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
                .orElseThrow(() -> new AssertionError(username + " 登录后未拿到登录态 cookie"));
        String value = setCookie.substring((BuyerSessionCookie.COOKIE_NAME + "=").length())
                .split(";", 2)[0];
        return new Cookie(BuyerSessionCookie.COOKIE_NAME, value);
    }

    private Long register(String username, String phone) throws Exception {
        mockMvc.perform(post("/buyer/register")
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("phone", phone)
                        .param("defaultPlace", "注册时的地点"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/buyer/login?msg=*"));

        Buyer buyer = buyerMapper.selectByUsername(username);
        assertThat(buyer).as("注册成功后应能查到账号 %s", username).isNotNull();
        return buyer.getId();
    }

    private Long insertProduct(String name, Long categoryId) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setDescription(name + "（配送测试数据）");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(1990L);
        product.setStock(50);
        product.setStatus(ProductStatus.ON_SALE);
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);
        return product.getId();
    }

    /** 直接构造一张订单（含一条明细），避免依赖下单链路 */
    private Long insertOrder(Long buyerId, String status, String orderNo) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO intent (order_no, product_id, buyer_id, buyer_name, phone, "
                        + "trade_address, status, created_at) VALUES (?,?,?,?,?,?,?,?)",
                orderNo, productId, buyerId, "买家", "13900003333",
                "浙江省杭州市西湖区文一西路 18 号", status, now);
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("INSERT INTO order_item (intent_id, product_id, product_name, price_cents, quantity, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                id, productId, "配送测试商品", 1990L, 1, now);
        return id;
    }

    private String statusOf(Long intentId) {
        return jdbc.queryForObject("SELECT status FROM intent WHERE id = ?", String.class, intentId);
    }
}

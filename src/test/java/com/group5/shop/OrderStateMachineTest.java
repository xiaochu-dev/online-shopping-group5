package com.group5.shop;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.portal.domain.Intent;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.portal.domain.OrderLine;
import com.group5.shop.portal.domain.OrderLineMapper;
import com.group5.shop.portal.service.OrderCreateProvider;
import com.group5.shop.portal.service.OrderStatusService;
import com.group5.shop.product.domain.Category;
import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4-03 / T4-06 / T4-08 验收测试：订单状态机与下单链路。
 *
 * <p>与 {@code SellerTradeFlowTest}（页面层，走 MockMvc）、{@code MyIntentCancelTest}
 * （客户侧页面）不同，本类<b>直接打服务层</b>：状态机的每一条规则都要能被单独断言，
 * 混在页面测试里一旦失败，很难看出是「按钮没渲染」还是「规则写错了」。</p>
 *
 * <h2>覆盖的规则</h2>
 * <ol>
 *   <li><b>正向流转</b>：created → confirmed → prepared → shipped → completed，一步一格，
 *       跳级与终局后再推进都被拒（B.1.1）；</li>
 *   <li><b>客户取消关闸线</b>：created / confirmed / prepared 可取消，进入 shipped 不可（B.1.2）；</li>
 *   <li><b>商家取消关闸线</b>：completed 之前都能取消，含已发货（B.1.3）；</li>
 *   <li><b>越权</b>：别人的单取消不了、别人的商品推不动，且报错不暴露单号是否存在；</li>
 *   <li><b>下单链路</b>（T4-06）：下单即扣库存、写订单头与明细、缺货逐件拦截不整单失败；</li>
 *   <li><b>取消回补</b>：库存回来、自动下架的商品恢复在售、重复取消不重复回补。</li>
 * </ol>
 *
 * @author 周到（迭代 4 · T4-03 / T4-06 / T4-08）
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderStateMachineTest {

    private static final Path TEST_DB =
            Path.of("target", "test-state-machine-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    /** 商品归属的商家；越权用例用另一个 id */
    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_SELLER_ID = 999L;

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private OrderStatusService orderStatus;

    @Autowired
    private OrderCreateProvider orderCreate;

    @Autowired
    private IntentMapper intentMapper;

    @Autowired
    private OrderLineMapper orderLineMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private BuyerMapper buyerMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    private Long categoryId;
    private Long buyerA;
    private Long buyerB;

    @BeforeAll
    void seed() {
        List<Category> categories = categoryMapper.selectEnabled();
        categoryId = categories.get(0).getId();
        buyerA = newBuyer("甲", "13800000001", "图书馆门口");
        buyerB = newBuyer("乙", "13800000002", "西门快递柜");
    }

    // ==================================================================
    // 一、正向流转（B.1.1）
    // ==================================================================

    @Test
    void 商家每推进一步只前进一格并在完成时落结束时间() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);

        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.CREATED);

        orderStatus.advance(orderId, SELLER_ID);
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.CONFIRMED);

        orderStatus.advance(orderId, SELLER_ID);
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.PREPARED);

        orderStatus.advance(orderId, SELLER_ID);
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.SHIPPED);
        assertThat(intentMapper.selectById(orderId).getFinishedAt())
                .as("还没完成，不该有结束时间").isNull();

        orderStatus.advance(orderId, SELLER_ID);
        Intent done = intentMapper.selectById(orderId);
        assertThat(done.getStatus()).isEqualTo(IntentStatus.COMPLETED);
        assertThat(done.getFinishedAt()).as("交易完成要落结束时间（我的订单按它倒序）").isNotNull();
    }

    @Test
    void 已完成的订单不能再推进() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        for (int i = 0; i < 4; i++) {
            orderStatus.advance(orderId, SELLER_ID);
        }
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.COMPLETED);

        assertThatThrownBy(() -> orderStatus.advance(orderId, SELLER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能再往后推进");
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.COMPLETED);
    }

    @Test
    void 已取消的订单不能再推进() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        orderStatus.cancelByBuyer(orderId, buyerA, "不想要了");

        assertThatThrownBy(() -> orderStatus.advance(orderId, SELLER_ID))
                .isInstanceOf(BusinessException.class);
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.CANCELLED);
    }

    // ==================================================================
    // 二、客户取消关闸线（B.1.2）
    // ==================================================================

    @Test
    void 客户在刚下单时可以取消() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);

        Intent cancelled = orderStatus.cancelByBuyer(orderId, buyerA, "地址填错了");

        assertThat(cancelled.getStatus()).isEqualTo(IntentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(IntentStatus.CANCELLED_BY_BUYER);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getFinishedAt()).as("取消即终局，结束时间要落").isNotNull();
        assertThat(cancelled.getFailReason()).isEqualTo("地址填错了");
    }

    @Test
    void 客户在备货完成之前仍可取消() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        orderStatus.advance(orderId, SELLER_ID);          // confirmed
        orderStatus.advance(orderId, SELLER_ID);          // prepared

        Intent cancelled = orderStatus.cancelByBuyer(orderId, buyerA, null);
        assertThat(cancelled.getStatus()).isEqualTo(IntentStatus.CANCELLED);
        assertThat(cancelled.getFailReason()).as("没填原因时给默认文案，不能是空串")
                .isEqualTo("客户取消了订单");
    }

    @Test
    void 商家发货后客户不能再取消() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        for (int i = 0; i < 3; i++) {
            orderStatus.advance(orderId, SELLER_ID);      // shipped
        }

        assertThatThrownBy(() -> orderStatus.cancelByBuyer(orderId, buyerA, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能再取消");
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.SHIPPED);
    }

    // ==================================================================
    // 三、商家取消关闸线（B.1.3）
    // ==================================================================

    @Test
    void 商家在已发货时仍可取消() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        for (int i = 0; i < 3; i++) {
            orderStatus.advance(orderId, SELLER_ID);      // shipped
        }

        Intent cancelled = orderStatus.cancelBySeller(orderId, SELLER_ID, "快递停发");
        assertThat(cancelled.getStatus()).isEqualTo(IntentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(IntentStatus.CANCELLED_BY_SELLER);
    }

    @Test
    void 商家在订单完成后不能取消() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);
        for (int i = 0; i < 4; i++) {
            orderStatus.advance(orderId, SELLER_ID);      // completed
        }

        assertThatThrownBy(() -> orderStatus.cancelBySeller(orderId, SELLER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能再取消");
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.COMPLETED);
    }

    // ==================================================================
    // 四、越权
    // ==================================================================

    @Test
    void 别的客户不能取消我的订单() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);

        assertThatThrownBy(() -> orderStatus.cancelByBuyer(orderId, buyerB, null))
                .as("越权必须按「不存在」处理，不能暴露别人的单号是否存在")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单不存在");
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.CREATED);
    }

    @Test
    void 别的商家不能推进我的订单() {
        Long productId = newProduct(10, 1990L);
        Long orderId = placeOrder(buyerA, productId, 1);

        assertThatThrownBy(() -> orderStatus.advance(orderId, OTHER_SELLER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前商家");
        assertThat(intentMapper.selectById(orderId).getStatus()).isEqualTo(IntentStatus.CREATED);
    }

    // ==================================================================
    // 五、下单链路（T4-06）
    // ==================================================================

    @Test
    void 下单即扣库存并写入订单头与明细() {
        Long productId = newProduct(5, 2590L);
        Long orderId = placeOrder(buyerA, productId, 2);

        Intent intent = intentMapper.selectById(orderId);
        assertThat(intent.getStatus()).isEqualTo(IntentStatus.CREATED);
        assertThat(intent.getOrderNo()).as("订单号必须生成，页面上要展示").startsWith("SO");
        assertThat(intent.getBuyerName()).isEqualTo("甲");
        assertThat(intent.getTradeAddress()).as("地址取买家默认地点").isEqualTo("图书馆门口");

        List<OrderLine> lines = orderLineMapper.selectByIntentId(orderId);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getQuantity()).isEqualTo(2);
        assertThat(lines.get(0).getPriceCents()).isEqualTo(2590);

        assertThat(productMapper.selectById(productId).getStock())
                .as("下单即扣库存（B 的口径，不是成交时才扣）").isEqualTo(3);
    }

    @Test
    void 多件下单合并成一张订单且逐件写明细() {
        Long first = newProduct(10, 1000L);
        Long second = newProduct(10, 2500L);

        OrderCreatePort.Result result = orderCreate.createOrder(buyerA, List.of(
                new CartCheckoutLine(first, 2, 1000L),
                new CartCheckoutLine(second, 1, 2500L)));

        assertThat(result.acceptedAll()).isTrue();
        assertThat(result.shortages()).isEmpty();

        List<OrderLine> lines = orderLineMapper.selectByIntentId(result.orderId());
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().mapToInt(OrderLine::getQuantity).sum())
                .as("明细数量合计 = 买的件数").isEqualTo(3);

        assertThat(productMapper.selectById(first).getStock()).isEqualTo(8);
        assertThat(productMapper.selectById(second).getStock()).isEqualTo(9);
    }

    @Test
    void 部分缺货时只下成功件并返回缺货清单() {
        Long enough = newProduct(10, 1000L);
        Long shortage = newProduct(1, 2000L);

        OrderCreatePort.Result result = orderCreate.createOrder(buyerA, List.of(
                new CartCheckoutLine(enough, 2, 1000L),
                new CartCheckoutLine(shortage, 3, 2000L)));

        // Q3：逐件拦截，不整单回滚、不拆单
        assertThat(result.partial()).isTrue();
        assertThat(result.acceptedProductIds()).containsExactly(enough);
        assertThat(result.shortages()).hasSize(1);
        assertThat(result.shortages().get(0).productId()).isEqualTo(shortage);
        assertThat(result.shortages().get(0).reason()).isEqualTo("库存不足");

        // 有货的那件真的扣了、也真的写进订单；缺货那件一件没动
        assertThat(productMapper.selectById(enough).getStock()).isEqualTo(8);
        assertThat(productMapper.selectById(shortage).getStock()).isEqualTo(1);
        assertThat(orderLineMapper.selectByIntentId(result.orderId())).hasSize(1);
    }

    @Test
    void 全部缺货时不建空订单() {
        Long soldOut = newProduct(1, 1000L);

        assertThatThrownBy(() -> orderCreate.createOrder(buyerA, List.of(
                new CartCheckoutLine(soldOut, 5, 1000L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        assertThat(productMapper.selectById(soldOut).getStock())
                .as("下不了单就不该扣库存").isEqualTo(1);
    }

    @Test
    void 下架商品直接拦下且不影响同单其余商品() {
        Long onSale = newProduct(10, 1000L);
        Long offShelf = newProduct(10, 1000L);
        Product patch = new Product();
        patch.setId(offShelf);
        patch.setStatus(ProductStatus.DELISTED);
        productMapper.updateById(patch);

        OrderCreatePort.Result result = orderCreate.createOrder(buyerA, List.of(
                new CartCheckoutLine(onSale, 1, 1000L),
                new CartCheckoutLine(offShelf, 1, 1000L)));

        assertThat(result.acceptedProductIds()).containsExactly(onSale);
        assertThat(result.shortages().get(0).reason()).isEqualTo("已下架");
        assertThat(productMapper.selectById(offShelf).getStock())
                .as("下架商品不该被扣库存").isEqualTo(10);
    }

    // ==================================================================
    // 六、取消回补
    // ==================================================================

    @Test
    void 客户取消后库存回补且自动下架的商品恢复在售() {
        // 库存 1：下一件就归零并自动下架，正好验证「回补要连带恢复上架」
        Long productId = newProduct(1, 1000L);
        Long orderId = placeOrder(buyerA, productId, 1);

        Product afterOrder = productMapper.selectById(productId);
        assertThat(afterOrder.getStock()).isZero();
        assertThat(afterOrder.getStatus()).as("扣到 0 自动下架（StockService 用的是 off_shelf）")
                .isEqualTo("off_shelf");
        assertThat(afterOrder.getOffReason()).isEqualTo("auto");

        orderStatus.cancelByBuyer(orderId, buyerA, "临时不要了");

        Product afterCancel = productMapper.selectById(productId);
        assertThat(afterCancel.getStock()).as("取消要回补库存").isEqualTo(1);
        assertThat(afterCancel.getStatus())
                .as("因售罄自动下架的商品，回补后要恢复在售，否则客户看到的是「已下架」")
                .isEqualTo(ProductStatus.ON_SALE);
        assertThat(afterCancel.getOffReason()).isNull();
    }

    @Test
    void 重复取消不会重复回补库存() {
        Long productId = newProduct(2, 1000L);
        Long orderId = placeOrder(buyerA, productId, 1);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(1);

        orderStatus.cancelByBuyer(orderId, buyerA, null);
        assertThat(productMapper.selectById(productId).getStock()).isEqualTo(2);

        // 第二次取消：状态已是终局，直接被拦；即便拦不住，台账也不会再回补一次
        assertThatThrownBy(() -> orderStatus.cancelByBuyer(orderId, buyerA, null))
                .isInstanceOf(BusinessException.class);
        assertThat(productMapper.selectById(productId).getStock())
                .as("重复取消不能把库存越补越多").isEqualTo(2);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 下一单（单件），返回订单 id */
    private Long placeOrder(Long buyerId, Long productId, int quantity) {
        OrderCreatePort.Result result = orderCreate.createOrder(buyerId,
                List.of(new CartCheckoutLine(productId, quantity, 0L)));
        return result.orderId();
    }

    private Long newProduct(int stock, long priceCents) {
        Product product = new Product();
        product.setSellerId(SELLER_ID);
        product.setCategoryId(categoryId);
        product.setName("状态机测试商品" + System.nanoTime());
        product.setDescription("测试用");
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

    private Long newBuyer(String name, String phone, String place) {
        Buyer buyer = new Buyer();
        buyer.setUsername(name);
        buyer.setPasswordHash("x");
        buyer.setSalt("y");
        buyer.setPhone(phone);
        buyer.setDefaultPlace(place);
        buyer.setCreatedAt(System.currentTimeMillis());
        buyerMapper.insert(buyer);
        return buyer.getId();
    }
}

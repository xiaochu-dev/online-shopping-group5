package com.group5.shop;

import com.group5.shop.buyer.service.BuyerAuthService;
import com.group5.shop.cart.port.CartCheckoutLine;
import com.group5.shop.cart.port.OrderCreatePort;
import com.group5.shop.portal.port.StockBatchPort;
import com.group5.shop.portal.service.OrderStatusService;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockConcurrencyTest {

    private static final Path TEST_DB = Path.of("target", "test-stock-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private BuyerAuthService buyers;
    @Autowired
    private ProductMapper products;
    @Autowired
    private OrderCreatePort orders;
    @Autowired
    private OrderStatusService orderStatus;
    @Autowired
    private StockBatchPort stock;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentMultiItemOrdersRejectOnlyShortLinesAndNeverOversell() throws Exception {
        Long limited = product("限量商品", 5);
        Long other = product("库存充足商品", 4);
        List<CartCheckoutLine> lines = List.of(line(limited, 2), line(other, 1));
        List<Long> buyerIds = List.of(buyer("stock_a", "13900002001"), buyer("stock_b", "13900002002"),
                buyer("stock_c", "13900002003"), buyer("stock_d", "13900002004"));

        CountDownLatch ready = new CountDownLatch(buyerIds.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(buyerIds.size());
        List<OrderCreatePort.Result> results = new ArrayList<>();
        try {
            List<Future<OrderCreatePort.Result>> requests = new ArrayList<>();
            for (Long buyerId : buyerIds) {
                requests.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("未收到统一下单信号");
                    }
                    return orders.createOrder(buyerId, lines);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<OrderCreatePort.Result> request : requests) {
                results.add(request.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).extracting(OrderCreatePort.Result::orderId).doesNotHaveDuplicates();
        assertThat(results).allSatisfy(result -> assertThat(result.acceptedProductIds()).contains(other));
        assertThat(results).filteredOn(result -> result.acceptedProductIds().contains(limited)).hasSize(2);
        assertThat(results).filteredOn(OrderCreatePort.Result::partial).hasSize(2)
                .allSatisfy(result -> assertThat(result.shortages()).singleElement().satisfies(shortage -> {
                    assertThat(shortage.productId()).isEqualTo(limited);
                    assertThat(shortage.requested()).isEqualTo(2);
                    assertThat(shortage.available()).isEqualTo(1);
                    assertThat(shortage.reason()).isEqualTo("库存不足");
                }));
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM order_item WHERE product_id = ?",
                Integer.class, limited)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM order_item WHERE product_id = ?",
                Integer.class, other)).isEqualTo(4);
        assertThat(products.selectById(limited).getStock()).isEqualTo(1);
        Product soldOut = products.selectById(other);
        assertThat(soldOut.getStock()).isZero();
        assertThat(soldOut.getStatus()).isEqualTo("off_shelf");
        assertThat(soldOut.getOffReason()).isEqualTo("auto");
    }

    @Test
    void cancellationRestoresOnceAndRelistsOnlyAutomaticallyOffShelfProducts() {
        Long buyerId = buyer("stock_cancel", "13900002010");
        Long automatic = product("归零自动下架商品", 2);
        Long manual = product("商家手动下架商品", 3);
        Long deleted = product("下单后删除的商品", 2);
        Long frozen = product("旧冻结状态商品", 2);
        jdbc.update("UPDATE product SET status = 'frozen' WHERE id = ?", frozen);
        var unsellable = stock.deduct(List.of(new StockBatchPort.Demand(frozen, 1)));
        assertThat(unsellable.deducted()).isEmpty();
        assertThat(unsellable.shortages()).containsExactly(new StockBatchPort.Shortage(frozen, 1, 0, "已下架"));
        assertThat(products.selectById(frozen).getStock()).isEqualTo(2);

        Long orderId = orders.createOrder(buyerId, List.of(line(automatic, 2), line(manual, 2), line(deleted, 1)))
                .orderId();
        assertThat(products.selectById(automatic).getStatus()).isEqualTo("off_shelf");
        jdbc.update("UPDATE product SET status = 'off_shelf', off_reason = 'manual' WHERE id = ?", manual);
        jdbc.update("DELETE FROM product WHERE id = ?", deleted);

        assertThat(orderStatus.cancelByBuyer(orderId, buyerId, "测试取消").getStatus()).isEqualTo("cancelled");
        stock.restore(orderId, List.of(new StockBatchPort.Demand(automatic, 2),
                new StockBatchPort.Demand(manual, 2), new StockBatchPort.Demand(deleted, 1)));

        Product relisted = products.selectById(automatic);
        assertThat(relisted.getStock()).isEqualTo(2);
        assertThat(relisted.getStatus()).isEqualTo("on_sale");
        assertThat(relisted.getOffReason()).isNull();
        Product stillOffShelf = products.selectById(manual);
        assertThat(stillOffShelf.getStock()).isEqualTo(3);
        assertThat(stillOffShelf.getStatus()).isEqualTo("off_shelf");
        assertThat(stillOffShelf.getOffReason()).isEqualTo("manual");
        assertThat(products.selectById(deleted)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_restore WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(1);
    }

    private Long buyer(String username, String phone) {
        return buyers.register(username, "pass12345", phone, "图书馆门口").getId();
    }

    private CartCheckoutLine line(Long productId, int quantity) {
        return new CartCheckoutLine(productId, quantity, 1000L);
    }

    private Long product(String name, int stockCount) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(101L);
        product.setName(name);
        product.setPriceCents(1000L);
        product.setStock(stockCount);
        product.setStatus("on_sale");
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        products.insert(product);
        return product.getId();
    }
}

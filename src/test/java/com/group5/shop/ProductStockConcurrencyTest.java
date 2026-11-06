package com.group5.shop;

import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.service.CategoryQueryService;
import com.group5.shop.product.service.ProductDetailService;
import com.group5.shop.product.service.ProductService;
import com.group5.shop.product.service.StockService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 黄程宇：5 个线程同时调用真实库存服务；不模拟数据库，不使用 sleep 制造时序。 */
@SpringBootTest
class ProductStockConcurrencyTest {
    private static final String RUN = UUID.randomUUID().toString();
    private static final Path ROOT = Path.of("target", "stock-concurrency-" + RUN).toAbsolutePath();
    private static final Path RECORD = Path.of("target", "stock-concurrency-" + RUN + ".csv");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT + ".db");
        registry.add("shop.product.upload-dir", () -> ROOT + "-uploads");
    }

    @Autowired StockService stocks;
    @Autowired ProductService products;
    @Autowired ProductDetailService details;
    @Autowired CategoryQueryService categories;
    @Autowired JdbcTemplate jdbc;

    @RepeatedTest(5)
    void insufficientStockRejectsExactlyTwoRequests(RepetitionInfo repetition) throws Exception {
        run(3, repetition.getCurrentRepetition());
    }

    @RepeatedTest(5)
    void lastUnitCanOnlyBeDeductedOnce(RepetitionInfo repetition) throws Exception {
        run(1, repetition.getCurrentRepetition());
    }

    @RepeatedTest(5)
    void exactStockIsExhaustedWithoutLostUpdates(RepetitionInfo repetition) throws Exception {
        run(5, repetition.getCurrentRepetition());
    }

    @RepeatedTest(5)
    void sufficientStockKeepsProductOnSale(RepetitionInfo repetition) throws Exception {
        run(7, repetition.getCurrentRepetition());
    }

    private void run(int initial, int repetition) throws Exception {
        String suffix = UUID.randomUUID().toString();
        categories.create("并发测试-" + suffix, 1L, 0);
        Long leaf = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, "并发测试-" + suffix);
        Product product = products.publish(1L, "并发库存商品", "29.90", leaf, initial, "", List.of(), List.of(), 0);
        // 留两名真实测试买家排队，验证售罄时的关联变更，而不调用他人下单/支付接口。
        for (int i = 0; i < 2; i++) {
            String username = "stock-" + suffix + "-" + i;
            jdbc.update("INSERT INTO buyer(username,password_hash,salt,phone,default_place,created_at) VALUES(?,'test-hash','test-salt','123','校门',1)", username);
            Long buyer = jdbc.queryForObject("SELECT id FROM buyer WHERE username=?", Long.class, username);
            jdbc.update("INSERT INTO intent(product_id,buyer_id,buyer_name,phone,trade_address,status,created_at) VALUES(?,?,'测试买家','123','校门','pending',?)", product.getId(), buyer, i + 1);
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        int successes = 0;
        try {
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("起跑信号超时");
                    try {
                        stocks.deduct(product.getId());
                        return true;
                    } catch (BusinessException exception) {
                        assertThat(exception.getMessage()).isEqualTo("商品库存不足，无法扣减");
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("5 个线程全部就绪").isTrue();
            start.countDown();
            // 未预期的 SQL、锁、断言错误通过 Future.get 向上抛出，不计为正常库存不足。
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) successes++;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        int expectedSuccesses = Math.min(5, initial);
        int remaining = initial - expectedSuccesses;
        Product actual = details.get(product.getId());
        assertThat(successes).isEqualTo(expectedSuccesses);
        assertThat(actual.getStock()).isEqualTo(remaining).isNotNegative();
        assertThat(initial - actual.getStock()).isEqualTo(successes);
        assertThat(actual.getStatus()).isEqualTo(remaining == 0 ? "off_shelf" : "on_sale");
        assertThat(actual.getOffReason()).isEqualTo(remaining == 0 ? "auto" : null);
        int failed = jdbc.queryForObject("SELECT count(*) FROM intent WHERE product_id=? AND status='failed' AND trade_result='failed' AND finished_at IS NOT NULL", Integer.class, product.getId());
        int pending = jdbc.queryForObject("SELECT count(*) FROM intent WHERE product_id=? AND status='pending' AND finished_at IS NULL", Integer.class, product.getId());
        assertThat(failed).isEqualTo(remaining == 0 ? 2 : 0);
        assertThat(pending).isEqualTo(remaining == 0 ? 0 : 2);
        // 库存耗尽后再扣一次，仍必须拒绝且不改变数据。
        if (remaining == 0) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stocks.deduct(product.getId()))
                .isInstanceOf(BusinessException.class).hasMessage("商品库存不足，无法扣减");
            assertThat(stocks.stockOf(product.getId())).isZero();
        }
        if (!Files.exists(RECORD)) Files.writeString(RECORD,
            "utc,initial_stock,threads,repetition,success,rejected,remaining,status,failed_queue,pending_queue\n", StandardCharsets.UTF_8);
        String row = String.format("%s,%d,5,%d,%d,%d,%d,%s,%d,%d%n", Instant.now(), initial, repetition,
            successes, 5 - successes, remaining, actual.getStatus(), failed, pending);
        Files.writeString(RECORD, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.print("STOCK_CONCURRENCY " + row);
    }
}

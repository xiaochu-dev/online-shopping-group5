package com.group5.shop;

import com.group5.shop.buyer.session.BuyerSessionCookie;
import com.group5.shop.common.constant.SessionKeys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AfterSalesTest {

    private static final String BUYER_PATH = "/my/after-sales";
    private static final String SELLER_PATH = "/admin/after-sales";
    private static final Path TEST_DB = Path.of("target", "after-sales-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BuyerSessionCookie sessionCookie;

    @BeforeAll
    void seedAccountsAndProducts() {
        jdbc.update("""
                INSERT INTO seller (id, username, password_hash, salt, created_at)
                VALUES (2, 'other_seller', 'test', 'test', 1)
                """);
        jdbc.update("""
                INSERT INTO buyer (id, username, password_hash, salt, phone, created_at)
                VALUES (1, 'after_sales_buyer', 'test', 'test', '13800000001', 1),
                       (2, 'another_buyer', 'test', 'test', '13800000002', 1)
                """);
        jdbc.update("""
                INSERT INTO product (id, seller_id, category_id, name, price_cents, stock, status,
                                     image_path, published_at, updated_at)
                SELECT 1, 1, MIN(id), '现名耳机', 8000, 10, 'on_sale', 'images/placeholder.png', 1, 1
                FROM category WHERE parent_id IS NOT NULL
                """);
        jdbc.update("""
                INSERT INTO product (id, seller_id, category_id, name, price_cents, stock, status,
                                     image_path, published_at, updated_at)
                SELECT 2, 2, MIN(id), '现名鼠标', 6000, 10, 'on_sale', 'images/placeholder.png', 1, 1
                FROM category WHERE parent_id IS NOT NULL
                """);
    }

    @Test
    void loginIsRequiredOnBothSides() throws Exception {
        mvc.perform(get(BUYER_PATH)).andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Fafter-sales"));
        mvc.perform(get(SELLER_PATH)).andExpect(redirectedUrl("/login?redirect=%2Fadmin%2Fafter-sales"));
    }

    @Test
    void buyerAndOwningSellerCompleteAnApplicationForTheSecondLine() throws Exception {
        long order = order("completed", true);
        String application = form(order, 2);
        mvc.perform(get("/my/orders").cookie(buyer(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(form(order, 1))))
                .andExpect(content().string(containsString(application)));
        mvc.perform(get(application).cookie(buyer(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("下单时鼠标")));

        MockMultipartFile proof = png();
        String buyerDetail = mvc.perform(request(order, 2, "鼠标故障", "双击异常", proof).cookie(buyer(1)))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        long requestId = Long.parseLong(buyerDetail.substring(BUYER_PATH.length() + 1));
        String sellerDetail = SELLER_PATH + "/" + requestId;
        long imageId = jdbc.queryForObject("SELECT id FROM after_sales_image WHERE request_id = ?", Long.class,
                requestId);

        mvc.perform(get(buyerDetail).cookie(buyer(1))).andExpect(status().isOk())
                .andExpect(content().string(containsString("待处理")))
                .andExpect(content().string(containsString(buyerDetail + "/images/" + imageId)));
        mvc.perform(get(BUYER_PATH).cookie(buyer(1))).andExpect(status().isOk())
                .andExpect(content().string(containsString(buyerDetail)))
                .andExpect(content().string(containsString("鼠标故障")));
        mvc.perform(get(SELLER_PATH).session(seller("other_seller"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("鼠标故障")));
        mvc.perform(get(sellerDetail).session(seller("other_seller"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("13800000001")));
        mvc.perform(get(sellerDetail + "/images/" + imageId).session(seller("other_seller")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(proof.getBytes()));

        mvc.perform(post(sellerDetail + "/resolve").session(seller("other_seller"))
                        .param("outcome", "REFUND").param("note", "尚未协商"))
                .andExpect(redirectedUrl(sellerDetail));
        mvc.perform(post(sellerDetail + "/negotiate").session(seller("other_seller")))
                .andExpect(redirectedUrl(sellerDetail));
        mvc.perform(get(buyerDetail).cookie(buyer(1)))
                .andExpect(content().string(containsString("协商中")));
        mvc.perform(post(sellerDetail + "/resolve").session(seller("other_seller"))
                        .param("outcome", "INVALID").param("note", "非法结果"))
                .andExpect(redirectedUrl(sellerDetail));
        mvc.perform(get(sellerDetail).session(seller("other_seller")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("登记并结束")));
        mvc.perform(post(sellerDetail + "/resolve").session(seller("other_seller"))
                        .param("outcome", "RETURN_REFUND").param("note", "已线下退货，退款 60 元"))
                .andExpect(redirectedUrl(sellerDetail));
        mvc.perform(post(sellerDetail + "/resolve").session(seller("other_seller"))
                        .param("outcome", "OTHER").param("note", "重复登记"))
                .andExpect(redirectedUrl(sellerDetail));
        mvc.perform(get(buyerDetail).cookie(buyer(1)))
                .andExpect(content().string(containsString("已处理")))
                .andExpect(content().string(containsString("退货退款")))
                .andExpect(content().string(containsString("已线下退货，退款 60 元")))
                .andExpect(content().string(not(containsString("重复登记"))));

        assertThat(jdbc.queryForObject("SELECT status FROM intent WHERE id = ?", String.class, order))
                .isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT stock FROM product WHERE id = 2", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE intent_id = ?", Long.class, order))
                .isZero();
    }

    @Test
    void otherBuyerAndSellerCannotViewOrChangeTheApplicationOrImages() throws Exception {
        long order = order("shipped", true);
        String ownerLink = mvc.perform(request(order, 2, "私有问题", "私有描述", png()).cookie(buyer(1)))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(ownerLink.substring(BUYER_PATH.length() + 1));
        long image = jdbc.queryForObject("SELECT id FROM after_sales_image WHERE request_id = ?", Long.class, id);
        String merchantLink = SELLER_PATH + "/" + id;

        mvc.perform(get(form(order, 2)).cookie(buyer(2))).andExpect(status().isNotFound());
        mvc.perform(request(order, 2, "其他买家提交", "拒绝", png()).cookie(buyer(2)))
                .andExpect(status().isNotFound());
        mvc.perform(get(ownerLink).cookie(buyer(2))).andExpect(status().isNotFound());
        mvc.perform(get(ownerLink + "/images/" + image).cookie(buyer(2))).andExpect(status().isNotFound());
        mvc.perform(get(merchantLink).session(seller("admin"))).andExpect(status().isNotFound());
        mvc.perform(get(merchantLink + "/images/" + image).session(seller("admin")))
                .andExpect(status().isNotFound());
        mvc.perform(post(merchantLink + "/negotiate").session(seller("admin"))).andExpect(status().isNotFound());
        mvc.perform(post(merchantLink + "/resolve").session(seller("admin"))
                        .param("outcome", "REFUND").param("note", "越权登记"))
                .andExpect(status().isNotFound());
        mvc.perform(get(SELLER_PATH).session(seller("admin")))
                .andExpect(content().string(not(containsString("私有问题"))));
        mvc.perform(get(ownerLink + "/images/" + (image + 1000)).cookie(buyer(1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shippedAndLegacyOrdersHaveAnEntryButCancelledAndUnrelatedProductsAreRejected() throws Exception {
        long legacy = order("completed", false);
        long cancelled = order("cancelled", true);
        long shipped = order("shipped", true);
        mvc.perform(get("/my/orders").cookie(buyer(1)))
                .andExpect(content().string(containsString(form(legacy, 1))))
                .andExpect(content().string(containsString(form(shipped, 2))))
                .andExpect(content().string(not(containsString(form(cancelled, 1)))));
        mvc.perform(request(legacy, 1, "旧单申请", "没有订单明细也能填写").cookie(buyer(1)))
                .andExpect(status().isFound());
        mvc.perform(get(form(legacy, 2)).cookie(buyer(1))).andExpect(status().isNotFound());
        mvc.perform(request(cancelled, 1, "取消订单", "不可售后").cookie(buyer(1)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("已发货或交易完成的订单可以申请售后")));
        mvc.perform(get(form(shipped, 2)).cookie(buyer(1))).andExpect(status().isOk());
    }

    @Test
    void invalidInputKeepsTheDraftAndDoesNotWriteAnyPartialApplication() throws Exception {
        long order = order("shipped", true);
        MockMultipartFile invalid = new MockMultipartFile("images", "invalid.png", "image/png", new byte[]{1, 2});
        MockMultipartFile oversized = new MockMultipartFile("images", "large.png", "image/png",
                new byte[5 * 1024 * 1024 + 1]);
        mvc.perform(request(order, 1, " ", "仍保留描述", png()).cookie(buyer(1)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("仍保留描述")));
        mvc.perform(request(order, 1, "有效标题", "上传了无效图片", png(), invalid).cookie(buyer(1)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("有效标题")));
        mvc.perform(request(order, 1, "有效标题", "图片过大", oversized).cookie(buyer(1)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("5 MB")));
        mvc.perform(request(order, 1, "有效标题", "图片太多", png(), png(), png(), png()).cookie(buyer(1)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("最多上传 3 张")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM after_sales_request WHERE intent_id = ?",
                Long.class, order)).isZero();
    }

    @Test
    void concurrentSubmissionsReuseOneApplicationAndOneBatchOfImages() throws Exception {
        long order = order("completed", true);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> submitAfter(start, order));
            var second = pool.submit(() -> submitAfter(start, order));
            start.countDown();
            String detail = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(detail);
            mvc.perform(get(form(order, 1)).cookie(buyer(1))).andExpect(redirectedUrl(detail));
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM after_sales_image img
                    JOIN after_sales_request a ON a.id = img.request_id WHERE a.intent_id = ?
                    """, Long.class, order)).isEqualTo(1);
            mvc.perform(request(order, 2, "同单另一件", "独立售后").cookie(buyer(1)))
                    .andExpect(status().isFound()).andExpect(header().string("Location", not(detail)));
        } finally {
            pool.shutdownNow();
        }
    }

    private String submitAfter(CountDownLatch start, long order) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(request(order, 1, "重复提交", "保留同一条申请", png()).cookie(buyer(1)))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
    }

    private MockMultipartHttpServletRequestBuilder request(long order, long product, String title, String description,
                                                           MockMultipartFile... files) {
        var request = multipart(form(order, product));
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        request.param("title", title).param("description", description);
        return request;
    }

    private long order(String status, boolean withLines) {
        String orderNo = "AF-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO intent (order_no, product_id, buyer_id, buyer_name, phone, status, created_at, finished_at)
                VALUES (?, 1, 1, '售后测试买家', '13800000001', ?, ?, ?)
                """, orderNo, status, now, now);
        long id = jdbc.queryForObject("SELECT id FROM intent WHERE order_no = ?", Long.class, orderNo);
        if (withLines) {
            jdbc.update("""
                    INSERT INTO order_item (intent_id, product_id, product_name, price_cents, quantity, created_at)
                    VALUES (?, 1, '下单时耳机', 8000, 1, ?), (?, 2, '下单时鼠标', 6000, 2, ?)
                    """, id, now, id, now);
        }
        return id;
    }

    private static String form(long order, long product) {
        return BUYER_PATH + "/new/" + order + "/" + product;
    }

    private Cookie buyer(long buyerId) {
        return new Cookie(BuyerSessionCookie.COOKIE_NAME, sessionCookie.issue(buyerId).getValue());
    }

    private MockHttpSession seller(String name) {
        var session = new MockHttpSession();
        session.setAttribute(SessionKeys.LOGIN_SELLER, name);
        session.setAttribute(SessionKeys.LOGIN_SELLER_ID,
                jdbc.queryForObject("SELECT id FROM seller WHERE username = ?", Long.class, name));
        return session;
    }

    private static MockMultipartFile png() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("images", "proof.png", "image/png", bytes.toByteArray());
    }
}

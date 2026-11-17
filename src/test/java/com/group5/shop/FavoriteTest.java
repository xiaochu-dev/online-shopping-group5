package com.group5.shop;

import com.group5.shop.buyer.service.BuyerAuthService;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.domain.ProductMapper;
import com.group5.shop.product.favorite.FavoriteService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FavoriteTest {

    private static final Path TEST_DB = Path.of("target", "test-favorite-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();
    private static final String FAVORITES = "/my/favorites";
    private static final String ADD = "/products/{id}/favorite";
    private static final String REMOVE = "/products/{id}/favorite/remove";
    private static final String PASSWORD = "pass12345";
    private static final String KEYBOARD = "收藏用键盘";
    private static final String LAMP = "收藏用台灯";
    private static final String BAG = "收藏用背包";

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private BuyerAuthService buyerAuth;
    @Autowired
    private ProductMapper products;
    @Autowired
    private FavoriteService favorites;
    @Autowired
    private JdbcTemplate jdbc;

    private long buyerA;
    private long buyerB;
    private long keyboard;
    private long lamp;
    private long bag;
    private long offShelf;
    private Cookie cookieA;
    private Cookie cookieB;

    @BeforeAll
    void seed() throws Exception {
        buyerA = buyerAuth.register("favorite_a", PASSWORD, "13900001001", null).getId();
        buyerB = buyerAuth.register("favorite_b", PASSWORD, "13900001002", null).getId();
        cookieA = login("favorite_a");
        cookieB = login("favorite_b");
        keyboard = product(KEYBOARD, "on_sale");
        lamp = product(LAMP, "on_sale");
        bag = product(BAG, "on_sale");
        offShelf = product("隐藏商品", "off_shelf");
    }

    @BeforeEach
    void clearFavorites() {
        jdbc.update("DELETE FROM favorite");
    }

    @Test
    void requiresBuyerLoginToViewOrChangeFavorites() throws Exception {
        mvc.perform(get(FAVORITES))
                .andExpect(redirectedUrlPattern("/buyer/login?redirect=*"));
        mvc.perform(post(ADD, keyboard))
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fproducts%2F" + keyboard));
        mvc.perform(post(REMOVE, keyboard))
                .andExpect(redirectedUrl("/buyer/login?redirect=%2Fmy%2Ffavorites"));

        assertThat(favorites.list(buyerA)).isEmpty();
    }

    @Test
    void savesProductAndRendersNamePriceAndRemoveButton() throws Exception {
        mvc.perform(post(ADD, keyboard).cookie(cookieA))
                .andExpect(redirectedUrl(FAVORITES))
                .andExpect(flash().attribute("msg", "已加入收藏"));
        mvc.perform(get(FAVORITES).cookie(cookieA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(KEYBOARD)))
                .andExpect(content().string(containsString("¥ 59.90")))
                .andExpect(content().string(containsString("取消收藏")))
                .andExpect(content().string(containsString("action=\"/cart/add\"")));
        mvc.perform(get("/products/{id}", keyboard).cookie(cookieA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/products/" + keyboard + "/favorite\"")));
        mvc.perform(post("/cart/add").cookie(cookieA).param("productId", Long.toString(keyboard)))
                .andExpect(redirectedUrlPattern("/products/" + keyboard + "?msg=*"));
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM cart WHERE buyer_id = ? AND product_id = ?",
                Integer.class, buyerA, keyboard)).isEqualTo(1);
    }

    @Test
    void repeatedAddCreatesOnlyOneRow() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(ADD, keyboard).cookie(cookieA))
                    .andExpect(redirectedUrl(FAVORITES));
        }

        assertThat(favorites.list(buyerA)).extracting(Product::getId).containsExactly(keyboard);
    }

    @Test
    void removesOwnFavoriteAndAllowsRetry() throws Exception {
        favorites.addToFavorites(buyerA, List.of(keyboard));
        mvc.perform(post(REMOVE, keyboard).cookie(cookieA))
                .andExpect(redirectedUrl(FAVORITES))
                .andExpect(flash().attribute("msg", "已取消收藏"));
        mvc.perform(post(REMOVE, keyboard).cookie(cookieA))
                .andExpect(redirectedUrl(FAVORITES));
        mvc.perform(get(FAVORITES).cookie(cookieA))
                .andExpect(content().string(containsString("还没有收藏")));
    }

    @Test
    void isolatesBuyerListsAndIgnoresForgedBuyerId() throws Exception {
        favorites.addToFavorites(buyerA, List.of(keyboard));
        favorites.addToFavorites(buyerB, List.of(lamp));

        mvc.perform(post(REMOVE, keyboard).cookie(cookieB)
                        .param("buyerId", Long.toString(buyerA)))
                .andExpect(redirectedUrl(FAVORITES));
        mvc.perform(get(FAVORITES).cookie(cookieB).param("buyerId", Long.toString(buyerA)))
                .andExpect(content().string(containsString(LAMP)))
                .andExpect(content().string(not(containsString(KEYBOARD))));
        assertThat(favorites.list(buyerA)).extracting(Product::getId).containsExactly(keyboard);
    }

    @Test
    void batchAddCountsDuplicatesAndIsIdempotentOnRetry() {
        favorites.addToFavorites(buyerA, List.of(keyboard));
        List<Long> selected = List.of(keyboard, lamp, lamp, bag);

        assertThat(favorites.addToFavorites(buyerA, selected)).isEqualTo(2);
        assertThat(favorites.addToFavorites(buyerA, selected)).isZero();
        assertThat(favorites.list(buyerA)).extracting(Product::getId)
                .containsExactlyInAnyOrder(keyboard, lamp, bag);
    }

    @Test
    void retainsOffShelfAndSoldOutProductsButSkipsUnknownProducts() throws Exception {
        long soldOut = product("售罄的收藏商品", "off_shelf");
        jdbc.update("UPDATE product SET stock = 0, off_reason = 'auto' WHERE id = ?", soldOut);
        assertThat(favorites.addToFavorites(buyerA, List.of(offShelf, soldOut, Long.MAX_VALUE)))
                .isEqualTo(2);
        assertThat(favorites.list(buyerA)).extracting(Product::getId)
                .containsExactlyInAnyOrder(offShelf, soldOut);

        mvc.perform(get(FAVORITES).cookie(cookieA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已下架")))
                .andExpect(content().string(containsString("无货")))
                .andExpect(content().string(not(containsString("action=\"/cart/add\""))));
    }

    @Test
    void keepsExistingFavoriteWhenProductIsTakenOffShelf() throws Exception {
        long productId = product("已收藏后下架的商品", "on_sale");
        favorites.addToFavorites(buyerA, List.of(productId));
        jdbc.update("UPDATE product SET status = 'off_shelf' WHERE id = ?", productId);

        mvc.perform(get(FAVORITES).cookie(cookieA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已收藏后下架的商品")))
                .andExpect(content().string(containsString("已下架")))
                .andExpect(content().string(not(containsString("href=\"/products/" + productId + "\""))));
        mvc.perform(post(REMOVE, productId).cookie(cookieA))
                .andExpect(redirectedUrl(FAVORITES));
        assertThat(favorites.list(buyerA)).isEmpty();
    }

    private Cookie login(String username) throws Exception {
        Cookie cookie = mvc.perform(post("/buyer/login").param("username", username).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie("BUYER_SESSION");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private long product(String name, String status) {
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(101L);
        product.setName(name);
        product.setPriceCents(5990L);
        product.setStatus(status);
        long now = System.currentTimeMillis();
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        products.insert(product);
        return product.getId();
    }
}

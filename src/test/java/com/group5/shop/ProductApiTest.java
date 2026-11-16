package com.group5.shop;

import com.group5.shop.common.api.PageResult;
import com.group5.shop.portal.contract.CategoryNode;
import com.group5.shop.portal.domain.ProductView;
import com.group5.shop.product.domain.Product;
import com.group5.shop.product.service.CategoryQueryService;
import com.group5.shop.product.service.ProductDetailService;
import com.group5.shop.product.service.ProductService;
import com.group5.shop.product.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 黄程宇：第三次评审商品接口测试，使用真实服务、SQLite、拦截器与模板。 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductApiTest {
    private static final Path ROOT = Path.of("target", "product-api-" + UUID.randomUUID()).toAbsolutePath();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT + ".db");
        registry.add("shop.product.upload-dir", () -> ROOT + "-uploads");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductService products;
    @Autowired ProductDetailService details;
    @Autowired StockService stocks;
    @Autowired CategoryQueryService categories;
    private MockHttpSession seller;
    private Long top, keyboardCategory, mouseCategory, bookCategory;
    private Product keyboard, mouse, book, hidden;

    @BeforeEach
    void fixtures() throws Exception {
        // 仅清理本测试类的 UUID 临时库，不访问 data/shop.db。
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM intent");
        jdbc.update("DELETE FROM product_image");
        jdbc.update("DELETE FROM product");
        String suffix = UUID.randomUUID().toString();
        top = category("数码-" + suffix, null);
        keyboardCategory = category("键盘", top);
        mouseCategory = category("鼠标", top);
        bookCategory = category("教材", category("图书-" + suffix, null));
        keyboard = publish("机械键盘A", keyboardCategory, 3, "<p>红轴，适合宿舍使用</p>");
        mouse = publish("无线鼠标B", mouseCategory, 2, "<p>静音，适合宿舍使用</p>");
        book = publish("软件工程教材C", bookCategory, 1, "<p>教材，无笔记</p>");
        hidden = publish("已售罄键盘D", keyboardCategory, 0, "<p>宿舍备用</p>");
        int order = 0;
        for (Product product : List.of(keyboard, mouse, book, hidden)) {
            jdbc.update("UPDATE product SET published_at=? WHERE id=?", ++order * 1000L, product.getId());
        }
        seller = (MockHttpSession) mvc.perform(post("/login")
                .param("username", "admin").param("password", "admin12345"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin"))
            .andReturn().getRequest().getSession(false);
        assertThat(seller).isNotNull();
    }

    @Test
    void listPaginatesInStableOrderWithoutHiddenProducts() throws Exception {
        PageResult<Product> first = page("1", "2", null, null);
        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getPage()).isEqualTo(1);
        assertThat(first.getSize()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.isHasPrev()).isFalse();
        assertThat(first.isHasNext()).isTrue();
        assertThat(first.getRecords()).extracting(Product::getId).containsExactly(book.getId(), mouse.getId());
        PageResult<Product> second = page("2", "2", null, null);
        assertThat(second.getRecords()).extracting(Product::getId).containsExactly(keyboard.getId());
        assertThat(second.getTotal()).isEqualTo(3);
        assertThat(second.isHasPrev()).isTrue();
        assertThat(second.isHasNext()).isFalse();
    }

    @Test
    void outOfRangePageReturnsEmptyRecords() throws Exception {
        PageResult<Product> result = page("9", "2", null, null);
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getPage()).isEqualTo(9);
    }

    @Test
    void invalidPageAndSizeUseDefaultsAndSizeHasUpperLimit() throws Exception {
        PageResult<Product> defaults = page("0", "0", null, null);
        assertThat(defaults.getPage()).isEqualTo(1);
        assertThat(defaults.getSize()).isEqualTo(12);
        assertThat(defaults.getRecords()).hasSize(3);
        assertThat(page("1", "999", null, null).getSize()).isEqualTo(100);
    }

    @Test
    void topCategoryIncludesBothChildrenAndReturnsTwoLevelTree() throws Exception {
        MvcResult response = listRequest("1", "12", top, null);
        assertThat(pageOf(response).getRecords()).extracting(Product::getId)
            .containsExactly(mouse.getId(), keyboard.getId());
        @SuppressWarnings("unchecked")
        List<CategoryNode> tree = (List<CategoryNode>) response.getModelAndView().getModel().get("categoryTree");
        CategoryNode selected = tree.stream().filter(node -> node.id().equals(top)).findFirst().orElseThrow();
        assertThat(selected.children()).extracting(CategoryNode::id).containsExactly(keyboardCategory, mouseCategory);
        assertThat(selected.children()).allSatisfy(node -> assertThat(node.children()).isEmpty());
    }

    @Test
    void leafCategoryExcludesSiblingAndHiddenProducts() throws Exception {
        assertThat(page("1", "12", keyboardCategory, null).getRecords())
            .extracting(Product::getId).containsExactly(keyboard.getId());
    }

    @Test
    void unknownAndDisabledCategoriesReturnEmptyResults() throws Exception {
        assertThat(page("1", "12", Long.MAX_VALUE, null).getTotal()).isZero();
        categories.setEnabled(keyboardCategory, false);
        assertThat(page("1", "12", keyboardCategory, null).getRecords()).isEmpty();
    }

    @Test
    void keywordMatchesNameAndDescriptionAndTrimsSpaces() throws Exception {
        assertThat(page("1", "12", null, "  机械键盘  ").getRecords())
            .extracting(Product::getId).containsExactly(keyboard.getId());
        assertThat(page("1", "12", null, "宿舍").getRecords())
            .extracting(Product::getId).containsExactly(mouse.getId(), keyboard.getId());
        assertThat(page("1", "12", null, "不存在的商品").getRecords()).isEmpty();
    }

    @Test
    void categoryAndKeywordAreCombined() throws Exception {
        assertThat(page("1", "12", mouseCategory, "宿舍").getRecords())
            .extracting(Product::getId).containsExactly(mouse.getId());
        assertThat(page("1", "12", bookCategory, "宿舍").getTotal()).isZero();
    }

    @Test
    void detailContainsCorrectProductPriceStockAndImageFallback() throws Exception {
        MvcResult response = mvc.perform(get("/products/{id}", keyboard.getId()))
            .andExpect(status().isOk()).andExpect(view().name("portal/product-detail"))
            .andExpect(model().attributeExists("view", "product", "queueSize"))
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andReturn();
        ProductView actual = (ProductView) response.getModelAndView().getModel().get("view");
        assertThat(actual.product().getId()).isEqualTo(keyboard.getId());
        assertThat(actual.product().getName()).isEqualTo("机械键盘A");
        assertThat(actual.product().getPriceCents()).isEqualTo(2990);
        assertThat(actual.stock()).isEqualTo(3);
        assertThat(actual.tradable()).isTrue();
        assertThat(actual.images()).containsExactly("images/placeholder.png");
        assertThat(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .contains("机械键盘A", "29.90");
    }

    @Test
    void missingAndOffShelfDetailsReturn404() throws Exception {
        for (Long id : List.of(Long.MAX_VALUE, hidden.getId())) {
            mvc.perform(get("/products/{id}", id)).andExpect(status().isNotFound())
                .andExpect(view().name("error/error"))
                .andExpect(model().attribute("message", "商品不存在或已下架"));
        }
    }

    @Test
    void frozenDetailRemainsVisibleButCannotBeOrdered() throws Exception {
        stocks.freeze(keyboard.getId());
        MvcResult response = mvc.perform(get("/products/{id}", keyboard.getId()))
            .andExpect(status().isOk()).andReturn();
        ProductView actual = (ProductView) response.getModelAndView().getModel().get("view");
        assertThat(actual.frozen()).isTrue();
        assertThat(actual.tradable()).isFalse();
        assertThat(actual.stock()).isEqualTo(3);
    }

    @Test
    void multipartPublishingPersistsFieldsAndMultipleProducts() throws Exception {
        for (String name : List.of("新键盘一", "新键盘二")) {
            mvc.perform(multipart("/admin/products").session(seller)
                    .param("name", name).param("price", "59.90")
                    .param("categoryId", keyboardCategory.toString()).param("stock", "4")
                    .param("description", "<p>商品介绍</p>"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/products"))
                .andExpect(flash().attribute("msg", "商品发布成功"));
            assertThat(jdbc.queryForObject("SELECT stock FROM product WHERE name=?", Integer.class, name)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT price_cents FROM product WHERE name=?", Long.class, name)).isEqualTo(5990);
            assertThat(jdbc.queryForObject("SELECT category_id FROM product WHERE name=?", Long.class, name)).isEqualTo(keyboardCategory);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "abc"})
    void invalidPriceIsRejectedWithoutWriting(String price) throws Exception {
        mvc.perform(multipart("/admin/products").session(seller).param("name", "不应保存")
                .param("price", price).param("categoryId", keyboardCategory.toString()).param("stock", "1"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/products/new"))
            .andExpect(flash().attributeExists("error"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isEqualTo(4);
    }

    @Test
    void thirdLevelCategoryIsRejectedWithoutWriting() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM category", Long.class);
        mvc.perform(post("/admin/categories").session(seller).param("name", "三级分类")
                .param("parentId", keyboardCategory.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/categories"))
            .andExpect(flash().attribute("error", "只能在启用的一级分类下新增二级分类"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Long.class)).isEqualTo(before);
    }

    @Test
    void stockZeroAutomaticallyDelistsAndDisappearsFromBuyerPages() throws Exception {
        mvc.perform(post("/admin/products/{id}/stock", keyboard.getId()).session(seller).param("stock", "0"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/products"))
            .andExpect(flash().attribute("msg", "操作成功"));
        Product actual = details.get(keyboard.getId());
        assertThat(actual.getStock()).isZero();
        assertThat(actual.getStatus()).isEqualTo("off_shelf");
        assertThat(actual.getOffReason()).isEqualTo("auto");
        assertThat(page("1", "12", keyboardCategory, null).getRecords()).isEmpty();
        mvc.perform(get("/products/{id}", keyboard.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/admin/products").session(seller).param("status", "off_shelf"))
            .andExpect(status().isOk()).andExpect(view().name("product/list"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("库存归零自动下架")));
    }

    @Test
    void negativeStockIsRejectedAndRefillingNeedsExplicitRelist() throws Exception {
        mvc.perform(post("/admin/products/{id}/stock", keyboard.getId()).session(seller).param("stock", "-1"))
            .andExpect(status().isFound()).andExpect(flash().attribute("error", "库存不能为负数"));
        assertThat(stocks.stockOf(keyboard.getId())).isEqualTo(3);
        mvc.perform(post("/admin/products/{id}/stock", hidden.getId()).session(seller).param("stock", "2"))
            .andExpect(status().isFound()).andExpect(flash().attribute("msg", "操作成功"));
        assertThat(details.get(hidden.getId()).getStatus()).isEqualTo("off_shelf");
        mvc.perform(post("/admin/products/{id}/relist", hidden.getId()).session(seller))
            .andExpect(status().isFound()).andExpect(flash().attribute("msg", "操作成功"));
        assertThat(details.get(hidden.getId()).getStatus()).isEqualTo("on_sale");
        assertThat(page("1", "12", keyboardCategory, null).getRecords())
            .extracting(Product::getId).containsExactly(hidden.getId(), keyboard.getId());
    }

    @Test
    void twoDeductionsUpdateStockAndAutomaticallyDelistLastUnit() throws Exception {
        stocks.deduct(mouse.getId());
        assertThat(details.get(mouse.getId()).getStock()).isEqualTo(1);
        assertThat(details.get(mouse.getId()).getStatus()).isEqualTo("on_sale");
        stocks.deduct(mouse.getId());
        assertThat(details.get(mouse.getId()).getStock()).isZero();
        assertThat(details.get(mouse.getId()).getStatus()).isEqualTo("off_shelf");
        assertThat(page("1", "12", mouseCategory, null).getRecords()).isEmpty();
    }

    private Long category(String name, Long parent) {
        categories.create(name, parent, 0);
        return jdbc.queryForObject("SELECT id FROM category WHERE name=? ORDER BY id DESC LIMIT 1", Long.class, name);
    }

    private Product publish(String name, Long category, int quantity, String description) {
        return products.publish(1L, name, "29.90", category, quantity, description, List.of(), List.of(), 0);
    }

    private MvcResult listRequest(String page, String size, Long category, String keyword) throws Exception {
        var request = get("/").param("page", page).param("size", size);
        if (category != null) request.param("categoryId", category.toString());
        if (keyword != null) request.param("keyword", keyword);
        return mvc.perform(request).andExpect(status().isOk()).andExpect(view().name("portal/product-list"))
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(model().attributeExists("pageResult", "categoryTree", "categoryNames"))
            .andReturn();
    }

    private PageResult<Product> page(String page, String size, Long category, String keyword) throws Exception {
        return pageOf(listRequest(page, size, category, keyword));
    }

    @SuppressWarnings("unchecked")
    private PageResult<Product> pageOf(MvcResult response) {
        return (PageResult<Product>) response.getModelAndView().getModel().get("pageResult");
    }
}

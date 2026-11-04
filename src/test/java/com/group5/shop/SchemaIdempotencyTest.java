package com.group5.shop;

import com.group5.shop.product.domain.CategoryMapper;
import com.group5.shop.portal.domain.IntentMapper;
import com.group5.shop.product.domain.ProductMapper;
import com.group5.shop.auth.domain.SellerMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T2-03 验收测试：建表脚本「可重复执行不报错」。
 *
 * <p>验收标准原文是「schema.sql 可重复执行不报错；字段与规格书一致」。
 * 这条不能靠肉眼检查 SQL 来判断，必须真的执行两遍：
 * <ol>
 *   <li>应用启动时已经执行过一遍（spring.sql.init.mode = always）；</li>
 *   <li>本用例再手工执行两遍，共三遍；</li>
 *   <li>断言：不抛异常、表数量不变、种子数据不翻倍。</li>
 * </ol>
 * </p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
class SchemaIdempotencyTest {

    private static final Path TEST_DB =
            Path.of("target", "test-schema-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SellerMapper sellerMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private IntentMapper intentMapper;

    @Test
    void 建表脚本重复执行应无异常且种子数据不翻倍() throws Exception {
        // 迭代 2 起建表脚本按模块拆成五个文件（见 application.yml），
        // 这里按同样顺序再跑两遍（加上启动时的一遍，共三遍）
        String[] scripts = {
                "schema/00-base.sql",
                "schema/01-buyer.sql",
                "schema/02-product.sql",
                "schema/03-intent.sql",
                "schema/04-payment.sql"
        };
        try (Connection connection = dataSource.getConnection()) {
            for (int round = 0; round < 2; round++) {
                for (String script : scripts) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
                }
            }
        }

        // 1. 六张业务表齐全（迭代 1 的三张 + 迭代 2 的 category/buyer + 本版的 payment）
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' ORDER BY name", String.class);
        assertEquals(List.of("buyer", "category", "intent", "payment", "product", "product_image", "seller"), tables);

        // 2. 种子数据不重复插入
        assertEquals(1L, sellerMapper.selectCount(null), "卖家账号只能有一条");
        // 分类分两级（挑战需求 A.1-2）：一级 5 个作分组，二级 19 个才是商品可挂的叶子
        // （14 个常见细分类 + 每个一级类目下 1 个「其他 XX」兜底叶子）。
        // 分别断言，避免只用总数掩盖「某一级漏建」或「某一级翻倍」。
        assertEquals(5L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE parent_id IS NULL", Long.class),
                "一级分类不能因重复执行而翻倍");
        assertEquals(19L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL", Long.class),
                "二级分类不能因重复执行而翻倍");
        assertEquals(24L, categoryMapper.selectCount(null), "分类总数应为一级 5 + 二级 19");

        // 3. 业务表为空但结构可用（能正常 count，说明列名与实体映射一致）
        assertEquals(0L, productMapper.selectCount(null));
        assertEquals(0L, intentMapper.selectCount(null));
    }

    @Test
    void 关键字段应符合迭代2要求() {
        // product 必须有分类字段与状态字段（T2-03 明确要求「新增分类 ID、状态字段」）
        List<String> productColumns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('product')", String.class);
        assertEquals(true, productColumns.contains("category_id"));
        assertEquals(true, productColumns.contains("status"));
        assertEquals(true, productColumns.contains("seller_id"));
        assertEquals(true, productColumns.contains("price_cents"));

        // intent 必须有口令码字段（T2-03「意向表含口令码字段」）
        List<String> intentColumns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('intent')", String.class);
        assertEquals(true, intentColumns.contains("token_code"));

        // 04-payment：支付单必须有关联意向、分计价金额与唯一流水号
        List<String> paymentColumns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('payment')", String.class);
        assertEquals(true, paymentColumns.contains("intent_id"));
        assertEquals(true, paymentColumns.contains("amount_cents"));
        assertEquals(true, paymentColumns.contains("status"));
        assertEquals(true, paymentColumns.contains("trade_no"));

        // T2-02：buyer 表字段与澄清结论一致（电话必填、不唯一）
        List<String> buyerColumns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('buyer')", String.class);
        assertEquals(true, buyerColumns.contains("username"));
        assertEquals(true, buyerColumns.contains("password_hash"));
        assertEquals(true, buyerColumns.contains("salt"));
        assertEquals(true, buyerColumns.contains("phone"));
        assertEquals(true, buyerColumns.contains("default_place"));

        // category 分类字典表存在且三列齐全
        List<String> categoryColumns = jdbcTemplate.queryForList(
                "SELECT name FROM pragma_table_info('category')", String.class);
        assertEquals(true, categoryColumns.contains("name"));
        assertEquals(true, categoryColumns.contains("sort_no"));
        assertEquals(true, categoryColumns.contains("status"));
    }
}

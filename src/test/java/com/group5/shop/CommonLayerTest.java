package com.group5.shop;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group5.shop.common.constant.CategoryStatus;
import com.group5.shop.common.constant.IntentStatus;
import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.common.entity.Category;
import com.group5.shop.common.entity.Intent;
import com.group5.shop.common.entity.Product;
import com.group5.shop.common.entity.Seller;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.mapper.CategoryMapper;
import com.group5.shop.common.mapper.IntentMapper;
import com.group5.shop.common.mapper.ProductMapper;
import com.group5.shop.common.mapper.SellerMapper;
import com.group5.shop.common.util.MoneyUtils;
import com.group5.shop.common.util.TimeUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-02 / T2-03 / T2-06 验收测试：SQLite 数据层 + MyBatis-Plus + 公共 Entity/Mapper。
 *
 * <p>覆盖的验收标准：
 * <ul>
 *   <li>T2-02「能对 SQLite 执行增删改查」→ {@link #商品应支持完整的增删改查()}</li>
 *   <li>T2-02「分页插件生效」→ {@link #分页查询应返回真实总数()}</li>
 *   <li>T2-03「种子数据正确」→ {@link #种子卖家与分类应已就绪()}</li>
 *   <li>T2-06「字段完整，可直接调用」→ 队列排序、口令码查询等</li>
 * </ul>
 * </p>
 *
 * @author 胡成溯（组长）
 */
@SpringBootTest
class CommonLayerTest {

    private static final Path TEST_DB =
            Path.of("target", "test-common-" + System.currentTimeMillis() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void useIsolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DB.toString().replace('\\', '/'));
    }

    @Autowired
    private SellerMapper sellerMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private IntentMapper intentMapper;

    // ------------------------------------------------------------------
    // T2-03 种子数据
    // ------------------------------------------------------------------

    @Test
    void 种子卖家与分类应已就绪() {
        Seller admin = sellerMapper.selectByUsername("admin");
        assertNotNull(admin, "默认卖家账号 admin 必须存在");
        assertEquals("admin", admin.getUsername());
        // 密码不以明文存储：库里只有 64 位十六进制哈希 + 16 位盐
        assertEquals(64, admin.getPasswordHash().length());
        assertEquals(16, admin.getSalt().length());

        List<Category> categories = categoryMapper.selectEnabled();
        assertEquals(5, categories.size(), "应预置 5 个启用分类");
        assertEquals("数码电子", categories.get(0).getName(), "分类应按 sort_no 升序");
        assertEquals(CategoryStatus.ENABLED, categories.get(0).getStatus());
    }

    // ------------------------------------------------------------------
    // T2-02 增删改查
    // ------------------------------------------------------------------

    @Test
    void 商品应支持完整的增删改查() {
        long now = TimeUtils.nowMillis();

        // 增
        Product product = new Product();
        product.setSellerId(1L);
        product.setCategoryId(1L);
        product.setName("测试用二手显示器");
        product.setDescription("24 寸 1080P，无坏点");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(129900L);          // 1299.00 元
        product.setStatus(ProductStatus.ON_SALE);
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        assertEquals(1, productMapper.insert(product));
        assertNotNull(product.getId(), "插入后应回填自增主键");

        // 查
        Product loaded = productMapper.selectById(product.getId());
        assertNotNull(loaded);
        assertEquals("测试用二手显示器", loaded.getName());
        assertEquals("1299.00", loaded.getPriceYuan(), "分转元应保留两位小数");
        assertEquals("在售", loaded.getStatusLabel());

        // 改：下架（T2-13 的状态流转依赖这个字段）
        loaded.setStatus(ProductStatus.DELISTED);
        loaded.setTradedAt(TimeUtils.nowMillis());
        loaded.setTradeResult("manual_delist");
        loaded.setUpdatedAt(TimeUtils.nowMillis());
        assertEquals(1, productMapper.updateById(loaded));

        Product afterUpdate = productMapper.selectById(product.getId());
        assertEquals(ProductStatus.DELISTED, afterUpdate.getStatus());
        assertEquals("manual_delist", afterUpdate.getTradeResult());
        assertEquals("已下架", afterUpdate.getStatusLabel());

        // 删
        assertEquals(1, productMapper.deleteById(product.getId()));
        assertNull(productMapper.selectById(product.getId()));
    }

    // ------------------------------------------------------------------
    // T2-02 分页插件
    // ------------------------------------------------------------------

    @Test
    void 分页查询应返回真实总数() {
        long now = TimeUtils.nowMillis();
        for (int i = 1; i <= 5; i++) {
            Product p = new Product();
            p.setSellerId(1L);
            p.setName("分页测试商品 " + i);
            p.setDescription("");
            p.setImagePath("images/placeholder.png");
            p.setPriceCents(1000L * i);
            p.setStatus(ProductStatus.ON_SALE);
            p.setPublishedAt(now + i);
            p.setUpdatedAt(now + i);
            productMapper.insert(p);
        }

        Page<Product> page = new Page<>(1, 2);
        Page<Product> result = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery()
                // 只统计本用例造的数据，避免与其它用例的遗留数据互相干扰
                .likeRight(Product::getName, "分页测试商品")
                .eq(Product::getStatus, ProductStatus.ON_SALE)
                .orderByDesc(Product::getPublishedAt));

        assertEquals(2, result.getRecords().size(), "每页 2 条");
        assertEquals(5L, result.getTotal(), "total 必须是数据库真实总数，而不是当前页条数");
        assertEquals(3L, result.getPages(), "5 条 / 每页 2 条 = 3 页");
        // 排序验证：publishedAt 最大的排第一
        assertEquals("分页测试商品 5", result.getRecords().get(0).getName());
    }

    // ------------------------------------------------------------------
    // T2-06 队列排序
    // ------------------------------------------------------------------

    @Test
    void 队列应按提交时间正序且同一毫秒用id兜底() {
        long now = TimeUtils.nowMillis();

        Product product = new Product();
        product.setSellerId(1L);
        product.setName("队列测试商品");
        product.setDescription("");
        product.setImagePath("images/placeholder.png");
        product.setPriceCents(5000L);
        product.setStatus(ProductStatus.ON_SALE);
        product.setPublishedAt(now);
        product.setUpdatedAt(now);
        productMapper.insert(product);

        // 先插入"较晚"的两条，再插入"最早"的一条，验证排序不是靠插入顺序
        Intent later1 = newIntent(product.getId(), "后到者甲", now + 2000);
        Intent later2 = newIntent(product.getId(), "后到者乙", now + 2000);
        Intent earliest = newIntent(product.getId(), "先到者", now + 1000);
        // 同一毫秒的两条：id 小的必须排在前面
        assertTrue(later1.getId() < later2.getId());

        List<Intent> queue = intentMapper.selectQueue(product.getId());
        assertEquals(3, queue.size());
        assertEquals("先到者", queue.get(0).getBuyerName(), "时间最早的在队首");
        assertEquals("后到者甲", queue.get(1).getBuyerName(), "同一毫秒按 id 升序");
        assertEquals("后到者乙", queue.get(2).getBuyerName());

        // 撤销一条后，剩余两条仍按原顺序（位次不落库，天然前移）
        earliest.setStatus(IntentStatus.CANCELLED);
        intentMapper.updateById(earliest);
        List<Intent> afterCancel = intentMapper.selectQueue(product.getId());
        assertEquals(2, afterCancel.size());
        assertEquals("后到者甲", afterCancel.get(0).getBuyerName());

        // 口令码可反查
        assertNotNull(intentMapper.selectByTokenCode(later1.getTokenCode()));
        assertNull(intentMapper.selectByTokenCode("不存在的口令码"));
    }

    private Intent newIntent(Long productId, String buyerName, long createdAt) {
        Intent intent = new Intent();
        intent.setProductId(productId);
        intent.setBuyerName(buyerName);
        intent.setPhone("13800000000");
        intent.setTokenCode(TimeUtils.randomTokenCode());
        intent.setStatus(IntentStatus.PENDING);
        intent.setCreatedAt(createdAt);
        intentMapper.insert(intent);
        return intent;
    }

    // ------------------------------------------------------------------
    // T2-01 金额换算工具（商品模块的校验基础）
    // ------------------------------------------------------------------

    @Test
    void 价格元分换算应正确处理边界() {
        assertEquals(990L, MoneyUtils.yuanToCents("9.90"));
        assertEquals(990L, MoneyUtils.yuanToCents("9.9"));
        assertEquals(1000L, MoneyUtils.yuanToCents("10"));
        assertEquals(50L, MoneyUtils.yuanToCents("0.50"));
        assertEquals("9.90", MoneyUtils.centsToYuan(990L));

        // 非法输入必须抛业务异常，而不是静默返回 0
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents(""));
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents(null));
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents("abc"));
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents("0"));
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents("-1"));
        assertThrows(BusinessException.class, () -> MoneyUtils.yuanToCents("9.999"));
    }

    // ------------------------------------------------------------------
    // 口令码
    // ------------------------------------------------------------------

    @Test
    void 口令码应为32位十六进制且不重复() {
        String a = TimeUtils.randomTokenCode();
        String b = TimeUtils.randomTokenCode();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertTrue(!a.equals(b));
    }
}

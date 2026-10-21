package com.group5.shop.portal.contract.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据库结构探测（临时过渡用，三人并行开发期间避免互相阻塞）。
 *
 * <p>本迭代三人同时改库：{@code buyer} 表归账号模块（胡），{@code product.stock}、
 * {@code category.parent_id}、{@code product_image} 归商品中心（黄）。门户若不判断这些结构在不在，
 * 只要队友晚一天落地，门户就整体起不来、也演示不了。</p>
 *
 * <p>所以门户侧把「结构是否就绪」探测一次并缓存：<b>结构在，走正式能力；结构不在，降级但页面可用</b>。
 * 队友的脚本一合入，下次启动自动切换，<b>无需改门户一行代码</b>。</p>
 *
 * <p>探测结果缓存后不再变化——本迭代运行时不会动态建表，缓存是安全的。</p>
 *
 * @author 周到
 */
@Component
public class SchemaCapability {

    private static final Logger log = LoggerFactory.getLogger(SchemaCapability.class);

    private final JdbcTemplate jdbc;

    /** 三次探测结果，null = 尚未探测 */
    private volatile Boolean productStock;
    private volatile Boolean categoryParent;
    private volatile Boolean productImage;
    private volatile Boolean buyerTable;

    public SchemaCapability(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 商品表是否已有 {@code stock} 字段（T2-10） */
    public boolean hasProductStock() {
        Boolean cached = productStock;
        if (cached == null) {
            cached = columnExists("product", "stock");
            productStock = cached;
            if (!cached) {
                log.warn("product.stock 尚未就绪（商品中心 T2-10 未合入）：库存按「不限」处理，成交不扣减");
            }
        }
        return cached;
    }

    /** 分类表是否已有 {@code parent_id}（T2-08） */
    public boolean hasCategoryParent() {
        Boolean cached = categoryParent;
        if (cached == null) {
            cached = columnExists("category", "parent_id");
            categoryParent = cached;
            if (!cached) {
                log.warn("category.parent_id 尚未就绪（商品中心 T2-08 未合入）：分类按单级展示与筛选");
            }
        }
        return cached;
    }

    /** 多图表是否存在（T2-11） */
    public boolean hasProductImage() {
        Boolean cached = productImage;
        if (cached == null) {
            cached = tableExists("product_image");
            productImage = cached;
            if (!cached) {
                log.warn("product_image 尚未就绪（商品中心 T2-11 未合入）：详情页只显示单张主图");
            }
        }
        return cached;
    }

    /** 买家表是否存在（账号模块 T2-02） */
    public boolean hasBuyerTable() {
        Boolean cached = buyerTable;
        if (cached == null) {
            cached = tableExists("buyer");
            buyerTable = cached;
            if (!cached) {
                log.warn("buyer 表尚未就绪（账号模块 T2-02 未合入）：下单入口会一直跳登录页");
            }
        }
        return cached;
    }

    // ------------------------------------------------------------------

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        if (!tableExists(table)) {
            return false;
        }
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        for (Map<String, Object> row : columns) {
            Object name = row.get("name");
            if (name != null && column.equalsIgnoreCase(name.toString())) {
                return true;
            }
        }
        return false;
    }
}

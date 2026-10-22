package com.group5.shop.portal.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code intent} 表迁移器（T2-16 / T2-17 / T2-18，owner：周到）。
 *
 * <p>把迭代 2 基线的 {@code intent} 升级成挑战需求 A 需要的结构，脚本见
 * {@code classpath:schema/03-intent.sql}。</p>
 *
 * <h2>为什么需要这个类，而不是只靠 schema/03-intent.sql</h2>
 * <ol>
 *   <li><b>建表脚本只能新建、改不了旧表</b>：{@code 03-intent.sql} 全是
 *       {@code CREATE ... IF NOT EXISTS}，对<b>已经存在</b>的旧库是空操作；
 *       老开发机上的 {@code intent} 还是「无 buyer_id、token_code NOT NULL」的形状，
 *       不处理就会在「下单不生成口令码」这一步写库失败；</li>
 *   <li><b>SQLite 改不了约束</b>：{@code token_code} 从 NOT NULL 改可空没有 ALTER 可用，
 *       只能重建整张表；</li>
 *   <li><b>表 owner 是模块，不是基座</b>：判定与迁移逻辑放在 intent 的 owner 包里，
 *       基座的 {@code schema/*.sql} 保持纯建表，别人不用关心我的表怎么演进。</li>
 * </ol>
 *
 * <p><b>数据取舍</b>：重建会清空 intent。按《挑战需求A-澄清问答记录》，存量匿名意向
 * 不并入任何客户，清空即符合口径；商品与账号数据不受影响。</p>
 *
 * <p><b>幂等</b>：以 {@code buyer_id} 是否已存在作为「迁移过」的标记，
 * 已迁移的库启动时只做一次 {@code PRAGMA} 探测，不写库。</p>
 *
 * @author 周到
 */
@Component
@Order(20)
public class IntentSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntentSchemaMigrator.class);

    /** 迁移完成的标记列：它存在就说明整张表已是挑战需求 A 的结构 */
    private static final String SENTINEL_TABLE = "intent";
    private static final String SENTINEL_COLUMN = "buyer_id";

    /** 迁移脚本位置（与 00/01/02 同目录，编号即 owner 划分） */
    private static final String SCRIPT_LOCATION = "schema/03-intent.sql";

    private final JdbcTemplate jdbc;

    public IntentSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (columnExists(SENTINEL_TABLE, SENTINEL_COLUMN)) {
            log.debug("intent 表已是挑战需求 A 结构（{}.{} 存在），跳过迁移",
                    SENTINEL_TABLE, SENTINEL_COLUMN);
            return;
        }
        if (!tableExists(SENTINEL_TABLE)) {
            // 全新库：schema/03-intent.sql 还没来得及建表（不该发生），跳过并提示，避免启动即崩
            log.warn("未找到 intent 表，跳过一次结构迁移；请确认 schema/03-intent.sql 已正确执行");
            return;
        }

        List<String> statements = readStatements();
        log.info("开始迁移 intent 表：{} 条语句（重建表以放开 token_code 非空约束）", statements.size());
        // 旧表结构与新 DDL 不兼容（缺列 + 多了 NOT NULL），整表重建；
        // 旧数据是匿名意向，按澄清结论不保留
        jdbc.execute("DROP TABLE IF EXISTS intent");
        for (String sql : statements) {
            jdbc.execute(sql);
        }
        log.info("intent 表迁移完成：新增 buyer_id / trade_address / finished_at / trade_result / fail_reason");
    }

    // ------------------------------------------------------------------
    // 脚本读取
    // ------------------------------------------------------------------

    /**
     * 读取迁移脚本并拆成单条语句。
     *
     * <p>只做最小解析：去掉整行 {@code --} 注释与空行，再按分号切分。
     * 本脚本不含字符串字面量里的分号，因此不需要完整的 SQL 词法分析。</p>
     */
    private List<String> readStatements() {
        String raw;
        try {
            raw = new ClassPathResource(SCRIPT_LOCATION)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移脚本失败：" + SCRIPT_LOCATION, e);
        }

        StringBuilder cleaned = new StringBuilder(raw.length());
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }

        List<String> statements = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // 结构探测
    // ------------------------------------------------------------------

    /** 表是否存在 */
    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }

    /** 列是否存在（PRAGMA table_info 每行一列，name 为列名） */
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

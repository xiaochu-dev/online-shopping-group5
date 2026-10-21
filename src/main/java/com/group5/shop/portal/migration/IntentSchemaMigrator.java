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
 * <h2>为什么需要这个类，而不是把 SQL 塞进 schema.sql</h2>
 * <ol>
 *   <li><b>SQLite 改不了约束</b>：{@code token_code} 要从 NOT NULL 改成可空，只能重建整张表，
 *       而重建脚本（CREATE 新表 → 搬数据 → DROP 旧表 → RENAME）<b>不能重复执行</b>，
 *       放进每次启动都跑的 {@code schema.sql} 里，第二次启动就会报表已存在；</li>
 *   <li><b>表 owner 是模块，不是基座</b>：{@code schema.sql} 是组长维护的公共脚本，
 *       intent 的 DDL 归买家门户模块，本类按「一个表只有一个 owner」落在自己包里；</li>
 *   <li><b>幂等靠结构判断</b>：以 {@code buyer_id} 是否已存在作为「迁移过」的标记，
 *       已迁移的库启动时直接跳过，不做任何写操作。</li>
 * </ol>
 *
 * <p><b>注意</b>：本类只做一次性结构迁移，不承担数据修补。存量匿名意见按澄清结论
 * 不并入任何客户，{@code buyer_id} 统一落 0。</p>
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

    /** 迁移脚本位置（与 schema.sql 同目录，按 01/02/03 编号区分 owner） */
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
            // 全新库：schema.sql 还没建 intent（不该发生），跳过并提示，避免启动即崩
            log.warn("未找到 intent 表，跳过一次结构迁移；请确认 schema.sql 已正确执行");
            return;
        }

        List<String> statements = readStatements();
        log.info("开始迁移 intent 表：{} 条语句（重建表以放开 token_code 非空约束）", statements.size());
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

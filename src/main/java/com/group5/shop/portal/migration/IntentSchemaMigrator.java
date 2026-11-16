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
 * {@code intent} 表迁移器（T2-16 / T2-17 / T2-18 / T4-01，owner：周到）。
 *
 * <p>脚本见 {@code classpath:schema/03-intent.sql}。三个版本的库都要能启动：</p>
 *
 * <table border="1">
 *   <caption>版本判定</caption>
 *   <tr><th>库的形状</th><th>判定依据</th><th>动作</th></tr>
 *   <tr><td>迭代 2 基线</td><td>没有 {@code buyer_id}</td><td>整表重建，存量匿名意向不保留</td></tr>
 *   <tr><td>迭代 3（挑战 A）</td><td>有 {@code buyer_id}、没有 {@code cancelled_by}</td>
 *       <td><b>换形保留数据</b>：旧状态按映射表翻译成五态</td></tr>
 *   <tr><td>迭代 4（挑战 B）</td><td>有 {@code cancelled_by}</td><td>跳过</td></tr>
 * </table>
 *
 * <h2>为什么 B 的迁移不能像 A 那样「删了重建」</h2>
 * <p>A 阶段可以清空，是因为存量的都是匿名意向、按澄清口径本就不并入任何客户。
 * B 阶段库里是真实产生过的订单，删掉等于抹掉历史，所以要「建新表 → 拷数据（翻译状态）→ 换名」。
 * SQLite 没有 {@code ALTER COLUMN}，改 CHECK 约束只能走这条路。</p>
 *
 * <h2>两个容易踩的 SQLite 坑</h2>
 * <ol>
 *   <li><b>改名前必须关外键</b>：{@code intent} 被 {@code order_item} 引用，
 *       外键约束开着时 {@code DROP TABLE} 会失败；</li>
 *   <li><b>改名前必须开 {@code legacy_alter_table}</b>：默认行为下重命名 {@code intent}
 *       会连带把 {@code order_item} 里记录的外键名改成新名字，换名后明细就挂到一张不存在的表上。
 *       老行为只改表名、不动别人对自己的引用，正好是这里需要的。</li>
 * </ol>
 *
 * <p><b>幂等</b>：以 {@code cancelled_by} 列是否存在作为「已是 B 结构」的标记，
 * 已迁移的库启动时只做一次 {@code PRAGMA} 探测，不写库。</p>
 *
 * @author 周到
 */
@Component
@Order(20)
public class IntentSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntentSchemaMigrator.class);

    private static final String TABLE = "intent";

    /** A 阶段的标记列 */
    private static final String SENTINEL_A = "buyer_id";

    /** B 阶段的标记列：它存在就说明已是五态结构 */
    private static final String SENTINEL_B = "cancelled_by";

    /** 迁移脚本位置（与 00/01/02 同目录，编号即 owner 划分） */
    private static final String SCRIPT_LOCATION = "schema/03-intent.sql";

    /** 换名期间的过渡表名 */
    private static final String STAGING = "intent_a3";

    private final JdbcTemplate jdbc;

    public IntentSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tableExists(TABLE)) {
            log.warn("未找到 intent 表，跳过结构迁移；请确认 schema/03-intent.sql 已正确执行");
            return;
        }
        if (columnExists(TABLE, SENTINEL_B)) {
            log.debug("intent 表已是挑战需求 B 结构（{}.{} 存在），跳过迁移", TABLE, SENTINEL_B);
            return;
        }
        if (!columnExists(TABLE, SENTINEL_A)) {
            rebuildFromBaseline();
            return;
        }
        upgradeToFiveStates();
    }

    // ------------------------------------------------------------------
    // 迭代 2 基线 → 当前结构（存量匿名意向不保留）
    // ------------------------------------------------------------------

    private void rebuildFromBaseline() {
        List<String> statements = readStatements();
        log.info("开始迁移 intent 表（基线）：{} 条语句，整表重建，存量匿名意向不保留", statements.size());
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
        for (String sql : statements) {
            jdbc.execute(sql);
        }
        log.info("intent 表迁移完成：新增 buyer_id / trade_address / finished_at / cancelled_by 等列");
    }

    // ------------------------------------------------------------------
    // 迭代 3（挑战 A）→ 迭代 4（挑战 B）：换形 + 状态翻译 + 保留数据
    // ------------------------------------------------------------------

    private void upgradeToFiveStates() {
        log.info("开始迁移 intent 表（A → B）：换形为五态订单头，保留订单数据");
        jdbc.execute("PRAGMA foreign_keys = OFF");
        jdbc.execute("PRAGMA legacy_alter_table = ON");
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + STAGING);
            jdbc.execute("ALTER TABLE " + TABLE + " RENAME TO " + STAGING);

            // 重建新的 intent（脚本里是 CREATE TABLE IF NOT EXISTS，此刻表已改名，会真的建）
            for (String sql : readStatements()) {
                jdbc.execute(sql);
            }

            // 拷数据：状态按《挑战需求B-澄清问答记录》§五 的映射表翻译
            jdbc.execute("""
                    INSERT INTO intent (id, order_no, product_id, buyer_id, buyer_name, phone,
                                        trade_address, token_code, status, cancelled_by, cancelled_at,
                                        trade_result, fail_reason, created_at, finished_at)
                    SELECT id,
                           NULL,
                           product_id,
                           buyer_id,
                           buyer_name,
                           phone,
                           trade_address,
                           token_code,
                           CASE status
                               WHEN 'pending' THEN 'created'
                               WHEN 'trading' THEN 'confirmed'
                               WHEN 'success' THEN 'completed'
                               WHEN 'failed'  THEN 'cancelled'
                               ELSE status
                           END,
                           NULL,
                           CASE WHEN status IN ('failed', 'cancelled', 'success', 'completed')
                                THEN finished_at ELSE NULL END,
                           trade_result,
                           fail_reason,
                           created_at,
                           finished_at
                    FROM """ + STAGING);

            jdbc.execute("DROP TABLE " + STAGING);
            log.info("intent 表迁移完成：pending→created、trading→confirmed、"
                    + "success→completed、failed→cancelled");
        } finally {
            jdbc.execute("PRAGMA legacy_alter_table = OFF");
            jdbc.execute("PRAGMA foreign_keys = ON");
        }
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

package com.group5.shop.product.adapter;

import com.group5.shop.common.constant.ProductStatus;
import com.group5.shop.portal.port.StockBatchPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 库存批量端口的实现（T4-23，商品域 → 订单域）。
 *
 * <p><b>归属说明</b>：本文件按任务工作表属于<b>黄程宇</b>（商品支撑）。2026-11-16 由周到
 * 写了这版<b>可用的脚手架</b>，目的是让 T4-06 的下单链路今天就能跑通、能演示；
 * 黄接手时可以整体重写（例如把「扣减 + 自动下架」收敛进 {@code StockService}），
 * 只要满足 {@link StockBatchPort} 的契约，订单域不需要任何改动。</p>
 *
 * <h2>为什么全部用条件 UPDATE</h2>
 * <p>「先查库存够不够、再扣」在并发下会超卖：两个请求都读到 stock=1，都判定够，
 * 于是扣成 -1。所以真正的判定必须写进 UPDATE 的 WHERE（{@code stock >= ?}），
 * 由数据库原子地决定谁扣得到。查出来的库存只用于生成「还差几件」的提示文案。</p>
 *
 * <h2>缺货为什么不抛异常</h2>
 * <p>11-15 答复 Q3：多件下单逐件校验，缺货那件提示、其余照常下单。抛出异常会让整单
 * 回滚，买家一件也买不到，与答复相反。</p>
 *
 * @author 周到（代黄程宇实现 T4-23 可用版，待其复核）
 */
@Component
public class StockBatchProvider implements StockBatchPort {

    /**
     * 自动下架后写入的状态值。
     *
     * <p>历史上有两个「下架」值并存：迭代 2 用 {@code delisted}（软删除语义），
     * 迭代 3 的商品服务（{@code StockService}）改用 {@code off_shelf} + {@code off_reason}
     * 区分「自动/手工」。这里与 {@code StockService} 保持一致，都用后者，
     * 否则回补时恢复上架的条件判断会对不上。</p>
     */
    private static final String OFF_SHELF = "off_shelf";

    private final JdbcTemplate jdbc;

    public StockBatchProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DeductResult deduct(List<Demand> demands) {
        List<Demand> deducted = new ArrayList<>();
        List<Shortage> shortages = new ArrayList<>();
        if (demands == null || demands.isEmpty()) {
            return new DeductResult(deducted, shortages);
        }

        long now = System.currentTimeMillis();
        for (Demand demand : demands) {
            if (demand.productId() == null || demand.quantity() <= 0) {
                shortages.add(new Shortage(demand.productId(), demand.quantity(), 0, "数量不合法"));
                continue;
            }

            // 扣减与「扣到 0 自动下架」在同一条语句里完成：
            // CASE 里的 stock 是**更新前**的值，所以 stock - ? 才是扣完的余量。
            int changed = jdbc.update(
                    "UPDATE product SET stock = stock - ?, updated_at = ?, "
                            + "status = CASE WHEN stock - ? = 0 THEN ? ELSE status END, "
                            + "off_reason = CASE WHEN stock - ? = 0 THEN 'auto' ELSE off_reason END "
                            + "WHERE id = ? AND stock >= ? AND status IN (?, ?)",
                    demand.quantity(), now,
                    demand.quantity(), OFF_SHELF,
                    demand.quantity(),
                    demand.productId(), demand.quantity(),
                    ProductStatus.ON_SALE, ProductStatus.FROZEN);

            if (changed == 1) {
                deducted.add(demand);
                continue;
            }

            // 扣不动：回读一次，把「为什么扣不动」翻译成给买家看的话
            Snapshot snapshot = snapshotOf(demand.productId());
            if (snapshot == null) {
                shortages.add(new Shortage(demand.productId(), demand.quantity(), 0, "商品不存在"));
            } else if (!snapshot.purchasable()) {
                shortages.add(new Shortage(demand.productId(), demand.quantity(), 0, "已下架"));
            } else {
                shortages.add(new Shortage(demand.productId(), demand.quantity(),
                        snapshot.stock(), "库存不足"));
            }
        }
        return new DeductResult(deducted, shortages);
    }

    @Override
    public void restore(Long orderId, List<Demand> demands) {
        if (orderId == null || demands == null || demands.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        // 台账去重：插入成功 = 第一次回补；被忽略 = 已经回补过，直接返回，绝不重复累加
        int first = jdbc.update(
                "INSERT OR IGNORE INTO stock_restore (order_id, restored_at) VALUES (?, ?)",
                orderId, now);
        if (first == 0) {
            return;
        }

        for (Demand demand : demands) {
            if (demand.productId() == null || demand.quantity() <= 0) {
                continue;
            }
            // 回补后的上架判断：只有「因库存归零被自动下架」的才恢复在售。
            // 商家手工下架是有意为之，不该被一次取消动作推翻（答复 Q6 的边界）。
            jdbc.update(
                    "UPDATE product SET stock = stock + ?, updated_at = ?, "
                            + "status = CASE WHEN status = ? AND off_reason = 'auto' THEN ? ELSE status END, "
                            + "off_reason = CASE WHEN status = ? AND off_reason = 'auto' THEN NULL ELSE off_reason END "
                            + "WHERE id = ?",
                    demand.quantity(), now,
                    OFF_SHELF, ProductStatus.ON_SALE,
                    OFF_SHELF,
                    demand.productId());
        }
    }

    /** 商品当下的状态与可售库存；商品不存在返回 {@code null} */
    private Snapshot snapshotOf(Long productId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, stock FROM product WHERE id = ?", productId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String status = row.get("status") == null ? "" : row.get("status").toString();
        Object stockValue = row.get("stock");
        int stock = stockValue == null ? 0 : ((Number) stockValue).intValue();
        boolean purchasable = (ProductStatus.ON_SALE.equals(status)
                || ProductStatus.FROZEN.equals(status)) && stock > 0;
        return new Snapshot(stock, purchasable);
    }

    /**
     * 商品的库存快照。
     *
     * @param stock       当前库存
     * @param purchasable 是否处于可售状态（在售/冻结且有货）
     */
    private record Snapshot(int stock, boolean purchasable) {
    }
}

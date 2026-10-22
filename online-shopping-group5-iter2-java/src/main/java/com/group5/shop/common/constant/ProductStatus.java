package com.group5.shop.common.constant;

/**
 * 商品状态常量（T2-06）。
 *
 * <p>数据库里 status 存的是字符串，Java 侧用常量而不是枚举，原因有两点：
 * <ol>
 *   <li>MyBatis-Plus 默认把枚举按 name() 存库，需要额外配置 {@code @EnumValue}，
 *       初学者容易踩坑；直接用 String 与 SQL 里写的值完全一致，肉眼可对；</li>
 *   <li>状态值同时出现在 schema.sql 的 CHECK 约束、页面判断、Java 代码三处，
 *       用常量集中管理，改一处即可。</li>
 * </ol>
 * </p>
 *
 * <p><b>迭代 2 实际只会出现 {@link #ON_SALE} 与 {@link #DELISTED}。</b>
 * {@link #FROZEN} 是为迭代 3 的「队首进入交易、自动冻结」预留的，
 * schema.sql 的 CHECK 约束已提前放开，届时无需改表。</p>
 *
 * 状态流转（迭代 2）：
 * <pre>
 *   发布 → on_sale ──卖家下架──→ delisted
 *              ↑                    │
 *              └────卖家重新上架──────┘
 * </pre>
 *
 * @author 胡成溯（组长）
 */
public final class ProductStatus {

    /** 在售：买家可见、可提交意向 */
    public static final String ON_SALE = "on_sale";

    /** 已下架：买家不可见、不可提交新意向；可重新上架 */
    public static final String DELISTED = "delisted";

    /** 交易中（冻结）：迭代 3 使用，迭代 2 不会写入该值 */
    public static final String FROZEN = "frozen";

    private ProductStatus() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /** 状态值 → 页面展示的中文名 */
    public static String labelOf(String status) {
        if (ON_SALE.equals(status)) {
            return "在售";
        }
        if (DELISTED.equals(status)) {
            return "已下架";
        }
        if (FROZEN.equals(status)) {
            return "交易中";
        }
        return "未知状态";
    }

    /** 页面徽章的样式类（配合 static/css/style.css 的 .tag-* 使用） */
    public static String cssOf(String status) {
        if (ON_SALE.equals(status)) {
            return "tag-green";
        }
        if (DELISTED.equals(status)) {
            return "tag-gray";
        }
        if (FROZEN.equals(status)) {
            return "tag-orange";
        }
        return "tag-gray";
    }

    /** 是否为合法状态值（与 schema.sql 的 CHECK 约束保持一致） */
    public static boolean isValid(String status) {
        return ON_SALE.equals(status) || DELISTED.equals(status) || FROZEN.equals(status);
    }
}

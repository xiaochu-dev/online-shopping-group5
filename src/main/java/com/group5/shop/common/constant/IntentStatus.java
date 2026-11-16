package com.group5.shop.common.constant;

/**
 * 订单状态（挑战需求 B.1.1，迭代 4 起 {@code intent} 由「购买意向」升级为「订单头」）。
 *
 * <h2>五个流转态 + 一个终局结果</h2>
 * <pre>
 *   created ──商家确认──→ confirmed ──备货完成──→ prepared ──开始发货──→ shipped ──交易完成──→ completed
 *      │                     │                      │
 *      └─── 客户可取消 ───────┴──────────────────────┘          客户在 shipped 之后不可取消
 *                                                             商家在 completed 之前都可取消
 * </pre>
 *
 * <p><b>取消不是第 6 个流转态</b>（11-15 助教答复 Q1）：{@link #CANCELLED} 与
 * {@link #COMPLETED} 平级，都是<b>终局结果</b>。谁取消的记在 {@code intent.cancelled_by}，
 * 不靠状态值区分——客户取消与商家取消的可取消时点本来就不同（Q6 的关闸表）。</p>
 *
 * <p><b>新流程不再产生 {@code failed}</b>：B 里「取消」与「交易失败」被合并成「已取消」。
 * {@code failed} 只作为迭代 1~3 的存量值参与迁移，迁移时并入 {@link #CANCELLED}。</p>
 *
 * <h2>LEGACY_* 常量为什么还在</h2>
 * <p>迭代 3 的排队流程（{@code SellerIntentService}、{@code PaymentService}）仍在写旧值，
 * 而 T4-03 才把它整段替换成五态流转。为了让「改模型」与「改流程」能分两次提交、
 * 每次都能跑绿，迁移期两套取值并存：schema 的 CHECK 同时接受旧值，
 * {@link #fromLegacy(String)} 负责把旧值映射成新值。
 * <b>T4-03 落地后这四个常量与 CHECK 里的旧值一并删除。</b></p>
 *
 * @author 周到（迭代 4 · T4-01）
 */
public final class IntentStatus {

    // ==================================================================
    // 五个流转态（B.1.1 原文）
    // ==================================================================

    /** 客户下单：订单已生成，等待商家确认 */
    public static final String CREATED = "created";

    /** 商家确认：商家已受理，开始备货 */
    public static final String CONFIRMED = "confirmed";

    /** 备货完成：货已备好，等待发货 */
    public static final String PREPARED = "prepared";

    /** 开始发货：货已出库，<b>客户从此刻起不可取消</b> */
    public static final String SHIPPED = "shipped";

    /** 交易完成：终局，买卖双方都不能再取消 */
    public static final String COMPLETED = "completed";

    // ==================================================================
    // 终局结果
    // ==================================================================

    /** 已取消：终局结果，与 {@link #COMPLETED} 平级；谁取消的看 cancelled_by */
    public static final String CANCELLED = "cancelled";

    // ==================================================================
    // 迁移兼容（迭代 1~3 的存量取值，T4-03 完成后删除）
    // ==================================================================

    /** @deprecated 迭代 3 的「排队中」，迁移后为 {@link #CREATED} */
    @Deprecated
    public static final String PENDING = "pending";

    /** @deprecated 迭代 3 的「交易中」，迁移后为 {@link #CONFIRMED} */
    @Deprecated
    public static final String TRADING = "trading";

    /** @deprecated 迭代 3 的「交易成功」，迁移后为 {@link #COMPLETED} */
    @Deprecated
    public static final String SUCCESS = "success";

    /** @deprecated 迭代 3 的「交易失败」，迁移后为 {@link #CANCELLED} */
    @Deprecated
    public static final String FAILED = "failed";

    /** 取消方：客户自己取消 */
    public static final String CANCELLED_BY_BUYER = "buyer";

    /** 取消方：商家取消 */
    public static final String CANCELLED_BY_SELLER = "seller";

    private IntentStatus() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================================================================
    // 展示
    // ==================================================================

    /**
     * 状态值 → 页面展示的中文名。
     *
     * @param status 状态值
     * @return 中文名；未知值返回「未知状态」
     */
    public static String labelOf(String status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case CREATED -> "客户下单";
            case CONFIRMED -> "商家确认";
            case PREPARED -> "备货完成";
            case SHIPPED -> "开始发货";
            case COMPLETED -> "交易完成";
            case CANCELLED -> "已取消";
            // 以下仅迁移期可见，避免存量单在页面上显示「未知状态」
            case PENDING -> "排队中（旧）";
            case TRADING -> "交易中（旧）";
            case SUCCESS -> "交易成功（旧）";
            case FAILED -> "交易失败（旧）";
            default -> "未知状态";
        };
    }

    /**
     * 取消方的中文名，用于「您已取消 / 商家已取消」这类文案。
     *
     * @param cancelledBy {@code buyer} / {@code seller} / {@code null}
     * @return 文案；未知返回空串
     */
    public static String cancelSideLabel(String cancelledBy) {
        if (CANCELLED_BY_BUYER.equals(cancelledBy)) {
            return "您已取消";
        }
        if (CANCELLED_BY_SELLER.equals(cancelledBy)) {
            return "商家已取消";
        }
        return "";
    }

    // ==================================================================
    // 状态机
    // ==================================================================

    /**
     * 商家推进一步后的状态。
     *
     * @param status 当前状态
     * @return 下一状态；终局或非法入参返回 {@code null}
     */
    public static String next(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case CREATED -> CONFIRMED;
            case CONFIRMED -> PREPARED;
            case PREPARED -> SHIPPED;
            case SHIPPED -> COMPLETED;
            default -> null;
        };
    }

    /**
     * 是否允许从 {@code from} 流转到 {@code to}。
     *
     * <p>只允许沿五态依次走一步：跳级（created → shipped）、倒退、终局后再改，全部为非法。</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 合法返回 true
     */
    public static boolean canTransit(String from, String to) {
        return next(from) != null && next(from).equals(to);
    }

    /**
     * 客户能否取消（B.1.2：开始发货<b>之前</b>，即 created / confirmed / prepared）。
     *
     * @param status 当前状态
     * @return 可取消返回 true
     */
    public static boolean canBuyerCancel(String status) {
        return CREATED.equals(status) || CONFIRMED.equals(status) || PREPARED.equals(status);
    }

    /**
     * 商家能否取消（B.1.3：交易完成<b>之前</b>，含 shipped）。
     *
     * @param status 当前状态
     * @return 可取消返回 true
     */
    public static boolean canSellerCancel(String status) {
        return canBuyerCancel(status) || SHIPPED.equals(status);
    }

    /**
     * 是否「进行中」（未到终局）：我的订单页的进行中区块按此分区。
     *
     * @param status 状态值
     * @return 进行中返回 true
     */
    public static boolean isActive(String status) {
        return CREATED.equals(status) || CONFIRMED.equals(status)
                || PREPARED.equals(status) || SHIPPED.equals(status);
    }

    /**
     * 是否终局（completed / cancelled，含存量 success / failed）。
     *
     * @param status 状态值
     * @return 终局返回 true
     */
    public static boolean isTerminal(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status)
                || SUCCESS.equals(status) || FAILED.equals(status);
    }

    /**
     * 是否可撤销——<b>过渡期兼容方法</b>。
     *
     * <p>迭代 3 的页面用 {@code intent.cancellable} 控制「撤销」按钮；T4-03 换成
     * {@link #canBuyerCancel(String)} 之后本方法删除。</p>
     *
     * @param status 状态值
     * @return 可撤销返回 true
     */
    public static boolean cancellable(String status) {
        return canBuyerCancel(status) || PENDING.equals(status);
    }

    // ==================================================================
    // 迁移映射
    // ==================================================================

    /**
     * 旧状态值 → 新状态值（《挑战需求B-澄清问答记录》§五 的映射表）。
     *
     * <ul>
     *   <li>{@code pending → created}：排队中 → 已下单待确认</li>
     *   <li>{@code trading → confirmed}：交易中 → 商家已确认（备货信息不可考，不臆造 prepared）</li>
     *   <li>{@code success → completed}</li>
     *   <li>{@code failed  → cancelled}：B 里取消与失败合并</li>
     * </ul>
     *
     * @param legacy 旧状态值
     * @return 新状态值；已是新值则原样返回
     */
    public static String fromLegacy(String legacy) {
        if (legacy == null) {
            return CREATED;
        }
        return switch (legacy) {
            case PENDING -> CREATED;
            case TRADING -> CONFIRMED;
            case SUCCESS -> COMPLETED;
            case FAILED -> CANCELLED;
            default -> legacy;
        };
    }
}

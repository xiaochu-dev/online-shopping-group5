package com.group5.shop.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 时间与随机串工具（T2-01 / T2-06）。
 *
 * <p>时间统一用「毫秒时间戳（long）」存库，与迭代 1 的 schema 保持一致：
 * SQLite 的 INTEGER 列直接存 long，查询排序、比较都最简单。</p>
 *
 * @author 胡成溯（组长）
 */
public final class TimeUtils {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);

    /** 十六进制字符表，用于生成口令码 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TimeUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /** 当前毫秒时间戳 */
    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    /** 毫秒时间戳 → "2026-10-18 18:30"，null 返回空串 */
    public static String formatDateTime(Long millis) {
        if (millis == null || millis <= 0) {
            return "";
        }
        return DATE_TIME.format(Instant.ofEpochMilli(millis));
    }

    /** 毫秒时间戳 → "2026-10-18"，null 返回空串 */
    public static String formatDate(Long millis) {
        if (millis == null || millis <= 0) {
            return "";
        }
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    /**
     * 生成 32 位随机十六进制串（128 bit），用作意向口令码。
     *
     * <p>为什么不用 {@code Math.random()}：它基于 48 位种子，可预测；
     * 这里用 {@link ThreadLocalRandom#nextInt} 填充 16 个字节，128 bit 全空间随机，
     * 满足「不可猜测」要求（NFR-05）。口令码还要配合 UNIQUE 约束兜底，
     * 万一碰撞由数据库直接拒绝，不会出现两个意向共用一个码。</p>
     */
    public static String randomTokenCode() {
        char[] chars = new char[32];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = HEX[random.nextInt(HEX.length)];
        }
        return new String(chars);
    }
}

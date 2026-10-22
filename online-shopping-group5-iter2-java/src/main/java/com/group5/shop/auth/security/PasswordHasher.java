package com.group5.shop.auth.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码哈希器（T2-05，沿用迭代 1 的算法）。
 *
 * <p><b>算法</b>：{@code sha256(salt + 明文密码)}，输出 64 位十六进制串；盐为 8 字节随机数
 * （16 位十六进制），与哈希一起存库。改密时<b>同时轮换盐</b>，避免同一密码得到同一哈希。</p>
 *
 * <p><b>为什么不是 MD5 或直接存明文</b>：MD5 已被证明可快速碰撞，且不加盐时相同密码
 * 哈希相同，彩虹表一查就破；加盐后每人的哈希都不同，彩虹表失效。</p>
 *
 * <p><b>与 Spring Security 的 BCrypt 相比</b>：BCrypt 内置慢哈希、抗 GPU 爆破，安全性更高。
 * 本迭代沿用迭代 1 的 sha256 + 盐，一是与原型保持「翻译式重写」一致、便于对照验收，
 * 二是避免为一个单账号系统引入整套 Spring Security 依赖。
 * 如后续要上生产，应换成 BCrypt（{@code BCryptPasswordEncoder}）并做数据迁移。</p>
 *
 * @author 胡成溯（组长）
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "SHA-256";

    /** 盐长度：8 字节 = 16 位十六进制 */
    private static final int SALT_BYTES = 8;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 计算密码哈希。
     *
     * @param rawPassword 明文密码
     * @param salt        盐（十六进制字符串）
     * @return 64 位十六进制哈希
     */
    public String hash(String rawPassword, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] bytes = digest.digest((salt + rawPassword).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会发生；真发生了说明运行环境异常
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /**
     * 校验密码。
     *
     * <p>用 {@link MessageDigest#isEqual} 做<b>常量时间比较</b>：
     * 普通的 {@code equals} 在第一个不同字符处就返回 false，
     * 攻击者可以通过响应时间差逐位猜出哈希（时序侧信道攻击）。</p>
     *
     * @param rawPassword  用户输入的明文
     * @param salt         库里的盐
     * @param expectedHash 库里的哈希
     * @return 是否匹配
     */
    public boolean matches(String rawPassword, String salt, String expectedHash) {
        if (rawPassword == null || salt == null || expectedHash == null) {
            return false;
        }
        String actual = hash(rawPassword, salt);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成新的随机盐。
     *
     * @return 16 位十六进制字符串
     */
    public String newSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

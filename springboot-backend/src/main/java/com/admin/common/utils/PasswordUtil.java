package com.admin.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希工具类
 *
 * <p>历史背景：早期版本使用未经加盐的 MD5 存储密码（见 {@link Md5Util}），
 * MD5 对密码哈希早已不适用（GPU 每秒可穷举数十亿次），且无盐意味着
 * 相同密码产生相同哈希，一旦数据库泄露可被彩虹表批量还原。
 *
 * <p>本类改用 <b>PBKDF2-HMAC-SHA256</b>（JDK 自带，无需引入任何第三方依赖），
 * 特点：
 * <ul>
 *   <li>每个密码独立随机盐（16 字节），杜绝彩虹表</li>
 *   <li>迭代 {@value #ITERATIONS} 次，显著抬高暴力破解成本</li>
 *   <li>哈希格式自描述：{@code pbkdf2$迭代次数$盐$哈希}，便于将来平滑升级参数</li>
 * </ul>
 *
 * <p>关于旧密码兼容：{@link #verify(String, String)} 会自动识别历史 MD5 哈希并校验，
 * 调用方可通过 {@link #needsUpgrade(String)} 判断是否需要在登录成功后
 * 透明地把密码升级为新的哈希格式。旧数据无需强制重置密码。
 */
public final class PasswordUtil {

    /** 哈希标识前缀，用于区分于历史 MD5 值（MD5 为 32 位十六进制，不含 $） */
    private static final String PREFIX = "pbkdf2";

    /** 迭代次数：兼顾安全性与登录响应速度 */
    private static final int ITERATIONS = 120_000;

    /** 盐长度（字节） */
    private static final int SALT_BYTES = 16;

    /** 派生密钥长度（bit） */
    private static final int KEY_LENGTH = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    /**
     * 生成密码哈希。
     *
     * @param rawPassword 明文密码
     * @return 形如 {@code pbkdf2$120000$<base64盐>$<base64哈希>} 的字符串
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] derived = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);

        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 校验密码，兼容历史 MD5 哈希。
     *
     * @param rawPassword    明文密码
     * @param storedPassword 数据库中保存的哈希（新格式或历史 MD5）
     * @return 是否匹配
     */
    public static boolean verify(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isEmpty()) {
            return false;
        }

        // 新格式
        if (storedPassword.startsWith(PREFIX + "$")) {
            String[] parts = storedPassword.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
                // 使用常量时间比较，避免通过响应时间侧信道推断哈希
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }

        // 历史 MD5 兼容：旧库中存的是无盐 MD5，必须继续允许登录，
        // 否则升级后所有老用户都无法登录。登录成功后由调用方透明升级。
        String legacy = Md5Util.md5(rawPassword);
        return legacy != null && constantTimeEquals(legacy, storedPassword);
    }

    /**
     * 判断该哈希是否需要升级为新的 PBKDF2 格式。
     *
     * <p>调用方在登录成功后据此透明升级，用户无感知。
     *
     * @param storedPassword 数据库中的哈希
     * @return true 表示仍为历史 MD5 格式，建议升级
     */
    public static boolean needsUpgrade(String storedPassword) {
        return storedPassword != null
                && !storedPassword.isEmpty()
                && !storedPassword.startsWith(PREFIX + "$");
    }

    /**
     * 判断一个字符串是否为本工具类生成的哈希格式。
     */
    public static boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX + "$");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希计算失败", e);
        }
    }

    /** 常量时间字符串比较 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}

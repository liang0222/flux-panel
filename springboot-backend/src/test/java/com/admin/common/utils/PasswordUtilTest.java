package com.admin.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordUtil 单元测试
 *
 * 重点覆盖两类风险：
 *  1. 新哈希算法本身是否正确、是否抗篡改
 *  2. 历史 MD5 密码能否继续登录（升级兼容性，一旦破坏会导致所有老用户无法登录）
 */
class PasswordUtilTest {

    // ------------------------------------------------------------------
    // 新格式：PBKDF2
    // ------------------------------------------------------------------

    @Test
    @DisplayName("哈希后可被正确校验")
    void hashThenVerify() {
        String raw = "MyS3cret!密码";
        String hash = PasswordUtil.hash(raw);

        assertTrue(PasswordUtil.verify(raw, hash), "正确密码应校验通过");
        assertFalse(PasswordUtil.verify("wrong-password", hash), "错误密码应校验失败");
    }

    @Test
    @DisplayName("相同密码每次哈希结果不同（盐随机）")
    void saltIsRandom() {
        String raw = "same-password";
        String h1 = PasswordUtil.hash(raw);
        String h2 = PasswordUtil.hash(raw);

        assertNotEquals(h1, h2, "两次哈希不应相同，否则盐未生效");
        // 但两者都必须能校验通过
        assertTrue(PasswordUtil.verify(raw, h1));
        assertTrue(PasswordUtil.verify(raw, h2));
    }

    @Test
    @DisplayName("哈希格式自描述：含算法标识、迭代次数、盐、摘要")
    void hashFormat() {
        String hash = PasswordUtil.hash("abc12345");
        String[] parts = hash.split("\\$");

        assertEquals(4, parts.length, "应为 pbkdf2$迭代次数$盐$摘要 四段");
        assertEquals("pbkdf2", parts[0]);
        assertTrue(Integer.parseInt(parts[1]) >= 100_000, "迭代次数不应过低");
        assertFalse(parts[2].isEmpty(), "盐不应为空");
        assertFalse(parts[3].isEmpty(), "摘要不应为空");
    }

    @Test
    @DisplayName("空密码或空哈希不应通过校验")
    void nullAndEmptySafety() {
        assertFalse(PasswordUtil.verify(null, PasswordUtil.hash("x")));
        assertFalse(PasswordUtil.verify("x", null));
        assertFalse(PasswordUtil.verify("x", ""));
        assertFalse(PasswordUtil.verify("", ""));
    }

    @Test
    @DisplayName("哈希为空密码时抛出异常而非静默生成")
    void hashRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(""));
    }

    @Test
    @DisplayName("被篡改的哈希不应通过校验")
    void tamperedHashRejected() {
        String hash = PasswordUtil.hash("real-password");

        // 篡改摘要部分
        String tampered = hash.substring(0, hash.length() - 4) + "AAAA";
        assertFalse(PasswordUtil.verify("real-password", tampered));

        // 篡改盐部分
        String[] parts = hash.split("\\$");
        String badSalt = "pbkdf2$" + parts[1] + "$" + "AAAAAAAAAAAAAAAAAAAAAA==" + "$" + parts[3];
        assertFalse(PasswordUtil.verify("real-password", badSalt));

        // 结构不完整
        assertFalse(PasswordUtil.verify("real-password", "pbkdf2$only-two-parts"));
    }

    // ------------------------------------------------------------------
    // 历史兼容：MD5 透明迁移
    // ------------------------------------------------------------------

    @Test
    @DisplayName("历史 MD5 密码仍可登录（关键兼容性）")
    void legacyMd5StillWorks() {
        // 这是 gost.sql 中管理员的真实密码哈希：MD5("admin_user")
        String legacyHash = "3c85cdebade1c51cf64ca9f3c09d182d";

        assertTrue(PasswordUtil.verify("admin_user", legacyHash),
                "默认管理员账号必须仍能登录，否则升级后无人能进面板");
        assertFalse(PasswordUtil.verify("wrong", legacyHash));
    }

    @Test
    @DisplayName("历史 MD5 哈希应被判定为需要升级")
    void legacyNeedsUpgrade() {
        String legacyHash = "3c85cdebade1c51cf64ca9f3c09d182d";
        String newHash = PasswordUtil.hash("whatever");

        assertTrue(PasswordUtil.needsUpgrade(legacyHash), "MD5 哈希应需要升级");
        assertFalse(PasswordUtil.needsUpgrade(newHash), "PBKDF2 哈希不应重复升级");
        assertFalse(PasswordUtil.needsUpgrade(null));
        assertFalse(PasswordUtil.needsUpgrade(""));
    }

    @Test
    @DisplayName("isHashed 能正确区分新旧格式")
    void isHashedDetection() {
        assertTrue(PasswordUtil.isHashed(PasswordUtil.hash("x")));
        assertFalse(PasswordUtil.isHashed("3c85cdebade1c51cf64ca9f3c09d182d"));
        assertFalse(PasswordUtil.isHashed(null));
    }

    @Test
    @DisplayName("迁移路径：MD5 校验通过 -> 重新哈希 -> 新哈希仍可校验")
    void migrationPath() {
        String rawPassword = "admin_user";
        String legacyHash = "3c85cdebade1c51cf64ca9f3c09d182d";

        // 1. 老用户用旧密码登录
        assertTrue(PasswordUtil.verify(rawPassword, legacyHash));
        assertTrue(PasswordUtil.needsUpgrade(legacyHash));

        // 2. 透明升级
        String upgraded = PasswordUtil.hash(rawPassword);

        // 3. 升级后仍能用同一密码登录
        assertTrue(PasswordUtil.verify(rawPassword, upgraded));
        assertFalse(PasswordUtil.needsUpgrade(upgraded));
    }

    @Test
    @DisplayName("中文与特殊字符密码正常工作")
    void unicodePassword() {
        String[] cases = {
                "中文密码测试",
                "p@$$w0rd!#%^&*()",
                "emoji🔐password",
                "a".repeat(200)
        };
        for (String raw : cases) {
            String hash = PasswordUtil.hash(raw);
            assertTrue(PasswordUtil.verify(raw, hash), "密码应校验通过: " + raw);
        }
    }
}

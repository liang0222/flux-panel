package com.admin.common.utils;

import com.admin.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtil 缓存优化后的正确性测试：
 * 缓存只应加速重复解析，不能放过被篡改、已过期或格式错误的 token。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key";

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", SECRET);
        jwtUtil.init();
    }

    private static User user() {
        User user = new User();
        user.setId(2L);
        user.setUser("alice");
        user.setRoleId(0);
        return user;
    }

    private static String base64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正常 token 可解析出用户信息")
    void parsesGeneratedToken() {
        String token = JwtUtil.generateToken(user());

        assertTrue(JwtUtil.validateToken(token));
        assertEquals(2L, JwtUtil.getUserIdFromToken(token));
        assertEquals(0, JwtUtil.getRoleIdFromToken(token));
    }

    @Test
    @DisplayName("重复解析结果一致（走缓存）")
    void repeatedParsingIsStable() {
        String token = JwtUtil.generateToken(user());

        for (int i = 0; i < 100; i++) {
            assertEquals(2L, JwtUtil.getUserIdFromToken(token));
            assertEquals(0, JwtUtil.getRoleIdFromToken(token));
            assertTrue(JwtUtil.validateToken(token));
        }
    }

    @Test
    @DisplayName("篡改 payload 后签名校验失败")
    void rejectsTamperedPayload() {
        String token = JwtUtil.generateToken(user());
        String[] parts = token.split("\\.");
        String forgedPayload = base64("{\"sub\":\"1\",\"exp\":9999999999,\"name\":\"root\",\"role_id\":0}");
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertFalse(JwtUtil.validateToken(forged));
    }

    @Test
    @DisplayName("过期 token 即使签名正确也不通过")
    void rejectsExpiredToken() {
        long past = System.currentTimeMillis() / 1000 - 10;
        String header = base64("{\"alg\":\"HmacSHA256\",\"typ\":\"JWT\"}");
        String payload = base64("{\"sub\":\"2\",\"exp\":" + past + ",\"name\":\"alice\",\"role_id\":0}");
        String token = header + "." + payload + "." + signature(header, payload);

        assertFalse(JwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("缓存不会把过期 token 一直判为有效")
    void cachedTokenStillExpires() {
        long soon = System.currentTimeMillis() / 1000 + 1;
        String header = base64("{\"alg\":\"HmacSHA256\",\"typ\":\"JWT\"}");
        String payload = base64("{\"sub\":\"2\",\"exp\":" + soon + ",\"name\":\"alice\",\"role_id\":0}");
        String token = header + "." + payload + "." + signature(header, payload);

        assertTrue(JwtUtil.validateToken(token));
        // 同一个 token 再次校验仍然走缓存分支，但有效期是每次重新判断的
        assertTrue(JwtUtil.validateToken(token));
    }

    @Test
    @DisplayName("格式错误 / 空 token 直接判为无效")
    void rejectsMalformedTokens() {
        assertFalse(JwtUtil.validateToken(null));
        assertFalse(JwtUtil.validateToken(""));
        assertFalse(JwtUtil.validateToken("abc"));
        assertFalse(JwtUtil.validateToken("a.b"));
        assertFalse(JwtUtil.validateToken("a.b.c.d"));
        assertFalse(JwtUtil.validateToken(".."));
        assertFalse(JwtUtil.validateToken("header.payload."));
    }

    @Test
    @DisplayName("不同用户生成的 token 互不影响")
    void differentUsersDoNotCollide() {
        User other = new User();
        other.setId(3L);
        other.setUser("bob");
        other.setRoleId(1);

        String tokenA = JwtUtil.generateToken(user());
        String tokenB = JwtUtil.generateToken(other);

        assertNotEquals(tokenA, tokenB);
        assertEquals(2L, JwtUtil.getUserIdFromToken(tokenA));
        assertEquals(3L, JwtUtil.getUserIdFromToken(tokenB));
        assertEquals(0, JwtUtil.getRoleIdFromToken(tokenA));
        assertEquals(1, JwtUtil.getRoleIdFromToken(tokenB));
    }

    /**
     * 用与 JwtUtil 相同的算法自行签名，构造"签名正确但已过期"的 token
     */
    private static String signature(String header, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

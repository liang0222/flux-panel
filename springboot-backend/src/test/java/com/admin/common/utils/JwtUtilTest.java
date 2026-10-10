package com.admin.common.utils;

import com.admin.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 单元测试
 *
 * 重点验证 token 版本号机制：
 * 它是「改密码后旧 token 立即失效」这一能力的基础，
 * 一旦版本号读写不一致，会导致两种严重后果之一：
 *   - 所有人都被强制登出（版本恒不匹配）
 *   - 改密码也踢不掉人（版本恒匹配）
 */
class JwtUtilTest {

    /**
     * 注入 SECRET_KEY。
     *
     * JwtUtil 的静态密钥依赖 Spring 的 @PostConstruct 注入，
     * 单元测试中没有容器，这里通过反射补上。
     */
    private static void ensureSecret() throws Exception {
        java.lang.reflect.Field field = JwtUtil.class.getDeclaredField("SECRET_KEY");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, "unit-test-secret-key-0123456789");
        }
    }

    private User buildUser(long id, int tokenVersion) {
        User u = new User();
        u.setId(id);
        u.setUser("tester");
        u.setRoleId(1);
        u.setTokenVersion(tokenVersion);
        return u;
    }

    @Test
    @DisplayName("生成的 token 可被校验通过")
    void generateAndValidate() throws Exception {
        ensureSecret();
        String token = JwtUtil.generateToken(buildUser(1L, 0));

        assertNotNull(token);
        assertEquals(3, token.split("\\.").length, "JWT 应为三段式");
        assertTrue(JwtUtil.validateToken(token), "刚签发的 token 应有效");
    }

    @Test
    @DisplayName("token 中携带正确的用户ID与版本号")
    void tokenCarriesClaims() throws Exception {
        ensureSecret();
        String token = JwtUtil.generateToken(buildUser(42L, 3));

        assertEquals(42, JwtUtil.getUserIdFromTokenAsInteger(token));
        assertEquals(3, JwtUtil.getTokenVersionFromToken(token));
    }

    @Test
    @DisplayName("tokenVersion 为 null 时按 0 处理")
    void nullVersionTreatedAsZero() throws Exception {
        ensureSecret();
        User u = buildUser(7L, 0);
        u.setTokenVersion(null);

        String token = JwtUtil.generateToken(u);
        // 不能抛异常，且应等价于版本 0
        assertEquals(0, JwtUtil.getTokenVersionFromToken(token));
    }

    @Test
    @DisplayName("被篡改的 token 校验失败")
    void tamperedTokenRejected() throws Exception {
        ensureSecret();
        String token = JwtUtil.generateToken(buildUser(1L, 0));

        // 改动 payload（签名会随之不匹配）
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "X" + "." + parts[2];
        assertFalse(JwtUtil.validateToken(tampered), "篡改后的 token 必须校验失败");

        // 改动签名
        String badSig = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "AA";
        assertFalse(JwtUtil.validateToken(badSig), "签名被改必须校验失败");
    }

    @Test
    @DisplayName("格式非法的 token 安全失败而非抛异常")
    void malformedTokenRejected() throws Exception {
        ensureSecret();
        assertFalse(JwtUtil.validateToken(null));
        assertFalse(JwtUtil.validateToken(""));
        assertFalse(JwtUtil.validateToken("not-a-jwt"));
        assertFalse(JwtUtil.validateToken("a.b"));
        assertFalse(JwtUtil.validateToken("!!!.???.###"));
    }

    @Test
    @DisplayName("版本号变化可用于吊销：新版本 token 与旧版本可区分")
    void versionEnablesRevocation() throws Exception {
        ensureSecret();
        String oldToken = JwtUtil.generateToken(buildUser(9L, 0));
        String newToken = JwtUtil.generateToken(buildUser(9L, 1));

        // 两个 token 都属于同一用户但版本不同 ——
        // 拦截器据此判定旧 token 失效，从而实现「改密码即踢下线」
        assertEquals(9, JwtUtil.getUserIdFromTokenAsInteger(oldToken));
        assertEquals(9, JwtUtil.getUserIdFromTokenAsInteger(newToken));
        assertEquals(0, JwtUtil.getTokenVersionFromToken(oldToken));
        assertEquals(1, JwtUtil.getTokenVersionFromToken(newToken));
        assertNotEquals(JwtUtil.getTokenVersionFromToken(oldToken),
                JwtUtil.getTokenVersionFromToken(newToken));
    }

    @Test
    @DisplayName("角色ID可正确读取")
    void roleIdReadable() throws Exception {
        ensureSecret();
        User u = buildUser(1L, 0);
        u.setRoleId(0); // 管理员
        String token = JwtUtil.generateToken(u);

        assertEquals(0, JwtUtil.getRoleIdFromToken(token));
    }
}

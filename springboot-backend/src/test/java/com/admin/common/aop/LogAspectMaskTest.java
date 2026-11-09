package com.admin.common.aop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验请求日志的敏感字段脱敏。
 * <p>
 * 背景：{@code /flow/upload}、{@code /flow/config} 由节点调用，参数里带 {@code secret}
 * （节点密钥，等同节点登录凭据）。改造前 {@link LogAspect} 把整个参数原样写进日志，
 * 生产 journal 与容器日志可直接读到明文 secret。本测试锁定脱敏后的行为：
 * 敏感值不再以明文出现，而非敏感内容与短参数完全不受影响。
 */
class LogAspectMaskTest {

    private static String mask(String input) throws Exception {
        Method m = LogAspect.class.getDeclaredMethod("maskSensitive", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    private static String truncate(String input) throws Exception {
        Method m = LogAspect.class.getDeclaredMethod("truncateForLog", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    @Test
    @DisplayName("节点上报参数中的 secret 被打码，不再出现明文")
    void secretIsMasked() throws Exception {
        String secret = "9e8b484f1bc844f980c164c156fbcd88";
        String payload = "{\"rawData\":\"{\\\"data\\\":\\\"abc\\\",\\\"encrypted\\\":true}\",\"secret\":\""
                + secret + "\"}";

        String masked = mask(payload);

        assertFalse(masked.contains(secret), "日志中不应出现 secret 明文");
        assertTrue(masked.contains("9e8b****cd88"), "应保留首尾各 4 位便于比对");
        assertTrue(masked.contains("rawData"), "非敏感字段必须原样保留");
    }

    @Test
    @DisplayName("短于 8 位的敏感值整体打码，不泄露任何片段")
    void shortSecretFullyMasked() throws Exception {
        String masked = mask("{\"secret\":\"abc\"}");
        assertEquals("{\"secret\":\"****\"}", masked);
    }

    @Test
    @DisplayName("密码与 token 字段同样脱敏")
    void passwordAndTokenAreMasked() throws Exception {
        String masked = mask("{\"password\":\"supersecretpwd\",\"token\":\"abcdefghijklmn\"}");
        assertFalse(masked.contains("supersecretpwd"));
        assertFalse(masked.contains("abcdefghijklmn"));
        assertTrue(masked.contains("supe****tpwd"));
        assertTrue(masked.contains("abcd****klmn"));
    }

    @Test
    @DisplayName("不含敏感字段的参数原样返回，行为与改造前一致")
    void plainPayloadUnchanged() throws Exception {
        String payload = "{\"name\":\"测试隧道\",\"remoteAddr\":\"1.2.3.4:8080\"}";
        assertEquals(payload, mask(payload));
    }

    @Test
    @DisplayName("null 与空串不抛异常")
    void nullAndEmptyAreSafe() throws Exception {
        assertEquals(null, mask(null));
        assertEquals("", mask(""));
    }

    @Test
    @DisplayName("截断逻辑保持原有行为：短参数不动，长参数带原长度标记")
    void truncateKeepsOriginalBehavior() throws Exception {
        String shortParam = "{\"a\":1}";
        assertEquals(shortParam, truncate(shortParam));

        String longParam = "x".repeat(1500);
        String truncated = truncate(longParam);
        assertTrue(truncated.startsWith("x".repeat(1000)));
        assertTrue(truncated.contains("原长度 1500 字符"));
    }
}

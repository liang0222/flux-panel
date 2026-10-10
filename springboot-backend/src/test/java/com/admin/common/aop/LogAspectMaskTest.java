package com.admin.common.aop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计日志脱敏测试
 *
 * 审计表会长期保存，一旦把明文密码写进去，等于把密码永久留在了数据库里。
 * 因此脱敏逻辑必须有测试兜底。
 */
class LogAspectMaskTest {

    @Test
    @DisplayName("JSON 中的密码字段被脱敏")
    void maskJsonPassword() {
        String input = "{\"username\":\"admin\",\"password\":\"MyPlainPassword123\"}";
        String masked = LogAspect.maskSensitive(input);

        assertFalse(masked.contains("MyPlainPassword123"), "明文密码不应出现在脱敏结果中");
        assertTrue(masked.contains("admin"), "非敏感字段应保留");
        assertTrue(masked.contains("******"), "应包含掩码");
    }

    @Test
    @DisplayName("多种密码/密钥字段名均被脱敏")
    void maskVariousSensitiveKeys() {
        String[] inputs = {
                "{\"pwd\":\"secret1\"}",
                "{\"newPassword\":\"secret2\"}",
                "{\"currentPassword\":\"secret3\"}",
                "{\"confirmPassword\":\"secret4\"}",
                "{\"secret\":\"secret5\"}",
                "{\"webhookSecret\":\"secret6\"}",
                "{\"feishuAppSecret\":\"secret7\"}",
                "{\"feishuEncryptKey\":\"secret8\"}",
                "{\"token\":\"secret9\"}"
        };
        for (String input : inputs) {
            String masked = LogAspect.maskSensitive(input);
            for (int i = 1; i <= 9; i++) {
                assertFalse(masked.contains("secret" + i),
                        "敏感值 secret" + i + " 不应出现在: " + masked);
            }
        }
    }

    @Test
    @DisplayName("key=value 形式（cookie/表单）也被脱敏")
    void maskKeyValueForm() {
        String input = "token=abcdef123456&password=plaintext&user=admin";
        String masked = LogAspect.maskSensitive(input);

        assertFalse(masked.contains("abcdef123456"), "token 值不应泄露");
        assertFalse(masked.contains("plaintext"), "密码值不应泄露");
        assertTrue(masked.contains("admin"), "非敏感参数应保留");
    }

    @Test
    @DisplayName("正常业务参数不被误伤")
    void normalParamsUntouched() {
        String input = "{\"name\":\"我的转发\",\"inPort\":20001,\"userId\":2,\"remark\":\"香港节点\"}";
        String masked = LogAspect.maskSensitive(input);

        assertTrue(masked.contains("我的转发"), "转发名不应被改动");
        assertTrue(masked.contains("20001"), "端口不应被改动");
        assertTrue(masked.contains("香港节点"), "备注不应被改动");
    }

    @Test
    @DisplayName("空值与 null 安全")
    void nullSafety() {
        assertNull(LogAspect.maskSensitive(null));
        assertEquals("", LogAspect.maskSensitive(""));
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        assertFalse(LogAspect.maskSensitive("{\"PASSWORD\":\"topsecret\"}").contains("topsecret"));
        assertFalse(LogAspect.maskSensitive("{\"PassWord\":\"topsecret\"}").contains("topsecret"));
    }

    @Test
    @DisplayName("多个敏感字段同时出现时全部脱敏")
    void multipleSensitiveFields() {
        String input = "{\"username\":\"u1\",\"password\":\"pw1\",\"secret\":\"s1\",\"token\":\"t1\"}";
        String masked = LogAspect.maskSensitive(input);

        assertFalse(masked.contains("pw1"));
        assertFalse(masked.contains("s1"));
        assertFalse(masked.contains("t1"));
        assertTrue(masked.contains("u1"));
    }
}

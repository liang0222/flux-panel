package com.admin.common.utils;

import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类，不使用第三方库实现
 */
@Component
public class JwtUtil {
    
    @Value("${jwt-secret}")
    private String secretKey;
    
    private static String SECRET_KEY;
    
    // token有效期。
    // 原实现为 90 天且无法吊销，一旦泄露在 90 天内都有效，风险过高。
    // 现改为 7 天，配合 token_version 机制可随时通过改密码强制下线。
    private static final long EXPIRE_TIME = 7L * 24 * 60 * 60 * 1000;
    // 算法
    private static final String ALGORITHM = "HmacSHA256";

    @PostConstruct
    public void init() {
        SECRET_KEY = this.secretKey;
    }

    /**
     * 生成JWT Token
     *
     * @param user 用户信息
     * @return 生成的JWT Token
     */
    public static String generateToken(User user) {
        try {
            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            Date expireDate = new Date(nowMillis + EXPIRE_TIME);

            // Header
            Map<String, Object> header = new HashMap<>();
            header.put("alg", ALGORITHM);
            header.put("typ", "JWT");
            String headerJson = JSON.toJSONString(header);
            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

            // Payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", user.getId().toString());
            payload.put("iat", now.getTime() / 1000); // 发布时间
            payload.put("exp", expireDate.getTime() / 1000); // 过期时间
            payload.put("user", user.getUser());
            payload.put("name", user.getUser());
            payload.put("role_id", user.getRoleId());
            // 版本号：登录后若密码被修改/重置，库中版本 +1，
            // 该 token 会在拦截器校验时被判定失效，实现「踢下线」。
            payload.put("token_version", user.getTokenVersion() == null ? 0 : user.getTokenVersion());

            String payloadJson = JSON.toJSONString(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            // Signature
            String signature = calculateSignature(encodedHeader, encodedPayload);

            // Token
            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("JWT token generation failed", e);
        }
    }

    /**
     * 验证JWT Token
     *
     * @param token JWT Token
     * @return 验证是否通过
     */
    public static boolean validateToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return false;
            }

            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String encodedHeader = parts[0];
            String encodedPayload = parts[1];
            String signature = parts[2];

            // 验证签名
            String expectedSignature = calculateSignature(encodedHeader, encodedPayload);
            if (!expectedSignature.equals(signature)) {
                return false;
            }

            // 验证过期时间
            String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            long exp = Long.parseLong(payload.get("exp").toString());
            long now = System.currentTimeMillis() / 1000;
            
            return exp > now;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从JWT Token中获取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public static Long getUserIdFromToken(String token) {
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Long.parseLong(payload.get("sub").toString());
    }

    /**
     * 从指定的 token 中读取用户ID（Integer 形式）。
     *
     * 与无参版本的区别：不依赖当前请求上下文，便于在拦截器中对任意 token 校验。
     *
     * @param token JWT Token
     * @return 用户ID，解析失败返回 null
     */
    public static Integer getUserIdFromTokenAsInteger(String token) {
        Long id = getUserIdFromToken(token);
        return id == null ? null : id.intValue();
    }

    /**
     * 从 token 中读取 token 版本号。
     *
     * 历史 token 中不含该字段，此时返回 0，
     * 与数据库中 token_version 为 null 时按 0 处理保持一致，
     * 保证升级后已登录用户不会被强制登出。
     *
     * @param token JWT Token
     * @return 版本号，缺失时为 0
     */
    public static Integer getTokenVersionFromToken(String token) {
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        Object version = payload.get("token_version");
        if (version == null) {
            return 0;
        }
        return Integer.parseInt(version.toString());
    }


    public static Integer getUserIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("sub").toString());
    }

    public static String getNameFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return payload.get("name").toString();
    }

    /**
     * 从JWT Token中获取用户角色ID
     *
     * @param token JWT Token
     * @return 角色ID
     */
    public static Integer getRoleIdFromToken(String token) {
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("role_id").toString());
    }

    @SneakyThrows
    public static Integer getRoleIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        if (token == null || token.isEmpty()) throw new Exception();
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("role_id").toString());
    }
    /**
     * 计算签名
     *
     * @param encodedHeader  编码后的头部
     * @param encodedPayload 编码后的负载
     * @return 签名
     * @throws Exception 签名计算异常
     */
    private static String calculateSignature(String encodedHeader, String encodedPayload) throws Exception {
        String content = encodedHeader + "." + encodedPayload;
        Mac hmac = Mac.getInstance(ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        hmac.init(secretKeySpec);
        byte[] signatureBytes = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }
} 
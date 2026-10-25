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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT工具类，不使用第三方库实现
 */
@Component
public class JwtUtil {
    
    @Value("${jwt-secret}")
    private String secretKey;
    
    private static String SECRET_KEY;
    
    // token有效期，7天
    private static final long EXPIRE_TIME = 90L * 24 * 60 * 60 * 1000;
    // 算法
    private static final String ALGORITHM = "HmacSHA256";

    // 优化：payload 解析结果与"签名已验证"的结论都做有界缓存。
    // 原来每个请求会解析 2~4 次 payload（每次都要 split 正则 + Base64 解码 + JSON 解析），
    // 并且每次都要重新做一次 HMAC-SHA256；token 在有效期内内容不会变化，可以安全复用。
    private static final int CACHE_SIZE = 512;
    private static final Map<String, Map<String, Object>> PARSED_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Map<String, Object>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });
    private static final Map<String, Long> VERIFIED_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private static volatile SecretKeySpec KEY_SPEC;
    private static final ThreadLocal<Mac> MAC_HOLDER = new ThreadLocal<>();

    @PostConstruct
    public void init() {
        SECRET_KEY = this.secretKey;
        KEY_SPEC = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM);
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

            // 已通过签名校验的 token 直接复用结论（仍然每次校验有效期）
            Long cachedExp = VERIFIED_CACHE.get(token);
            if (cachedExp != null) {
                return cachedExp > System.currentTimeMillis() / 1000;
            }

            String[] parts = splitToken(token);
            if (parts == null) {
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
            Map<String, Object> payload = decodePayload(encodedPayload);
            long exp = Long.parseLong(payload.get("exp").toString());
            long now = System.currentTimeMillis() / 1000;
            if (exp <= now) {
                return false;
            }
            // 签名与有效期都通过后才缓存，避免被伪造 token 污染缓存
            PARSED_CACHE.put(token, payload);
            VERIFIED_CACHE.put(token, exp);
            return true;
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
        return Long.parseLong(payloadOf(token).get("sub").toString());
    }


    public static Integer getUserIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        return Integer.parseInt(payloadOf(token).get("sub").toString());
    }

    public static String getNameFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        return payloadOf(token).get("name").toString();
    }

    /**
     * 从JWT Token中获取用户角色ID
     *
     * @param token JWT Token
     * @return 角色ID
     */
    public static Integer getRoleIdFromToken(String token) {
        return Integer.parseInt(payloadOf(token).get("role_id").toString());
    }

    @SneakyThrows
    public static Integer getRoleIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        if (token == null || token.isEmpty()) throw new Exception();
        return Integer.parseInt(payloadOf(token).get("role_id").toString());
    }
    /**
     * 取出 token 的 payload：优先命中缓存，未命中时解析后放入缓存
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(String token) {
        Map<String, Object> cached = PARSED_CACHE.get(token);
        if (cached != null) {
            return cached;
        }
        String[] parts = splitToken(token);
        Map<String, Object> payload = decodePayload(parts[1]);
        PARSED_CACHE.put(token, payload);
        return payload;
    }

    /**
     * 按 '.' 切分 token（用 indexOf 代替正则 split）
     *
     * @return 三段式返回数组，否则返回 null
     */
    private static String[] splitToken(String token) {
        if (token == null) {
            return null;
        }
        int first = token.indexOf('.');
        if (first <= 0) {
            return null;
        }
        int second = token.indexOf('.', first + 1);
        if (second <= first + 1 || second == token.length() - 1) {
            return null;
        }
        if (token.indexOf('.', second + 1) >= 0) {
            return null;
        }
        return new String[]{token.substring(0, first), token.substring(first + 1, second), token.substring(second + 1)};
    }

    private static Map<String, Object> decodePayload(String encodedPayload) {
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        return JSON.parseObject(decodedPayload, Map.class);
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
        Mac hmac = MAC_HOLDER.get();
        if (hmac == null) {
            hmac = Mac.getInstance(ALGORITHM);
            MAC_HOLDER.set(hmac);
        }
        SecretKeySpec keySpec = KEY_SPEC;
        if (keySpec == null) {
            keySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            KEY_SPEC = keySpec;
        }
        hmac.init(keySpec);
        byte[] signatureBytes = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }
} 
package com.admin.common.utils;

import com.admin.common.exception.UnauthorizedException;
import com.admin.entity.User;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    
    // 保持既有 90 天有效期，避免升级时意外改变登录行为。
    private static final long EXPIRE_TIME = 90L * 24 * 60 * 60 * 1000;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int CACHE_SIZE = 512;
    private static final ThreadLocal<Mac> MAC_HOLDER = new ThreadLocal<>();
    private static volatile SigningState signingState;

    private record VerifiedToken(Map<String, Object> claims, long expiresAt) {}

    private static final class SigningState {
        private final SecretKeySpec key;
        private final Map<String, VerifiedToken> tokens = Collections.synchronizedMap(
                new LinkedHashMap<String, VerifiedToken>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, VerifiedToken> eldest) {
                        return size() > CACHE_SIZE;
                    }
                });

        private SigningState(SecretKeySpec key) {
            this.key = key;
        }
    }

    @PostConstruct
    public void init() {
        String configured = secretKey == null ? "" : secretKey.strip();
        if (configured.getBytes(StandardCharsets.UTF_8).length < 32
                || configured.codePoints().distinct().limit(2).count() < 2
                || configured.equalsIgnoreCase("change-me-to-a-random-string")) {
            throw new IllegalStateException("JWT_SECRET must be randomly generated and contain at least 32 UTF-8 bytes");
        }
        // 已验证缓存与签名密钥绑定。重新初始化后旧缓存自然失效。
        signingState = new SigningState(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
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
            String signature = calculateSignature(signingState.key, encodedHeader, encodedPayload);

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
            return verifiedToken(token) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static VerifiedToken verifiedToken(String token) throws Exception {
        token = normalizeToken(token);
        SigningState state = signingState;
        if (token == null || token.isEmpty() || state == null) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        VerifiedToken cached = state.tokens.get(token);
        if (cached != null) {
            return cached.expiresAt() > now ? cached : null;
        }
        String[] parts = splitToken(token);
        if (parts == null) {
            return null;
        }
        String signature = calculateSignature(state.key, parts[0], parts[1]);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        Map<String, Object> claims = decodePayload(parts[1]);
        long expiresAt = Long.parseLong(claims.get("exp").toString());
        if (expiresAt <= now) {
            return null;
        }
        VerifiedToken result = new VerifiedToken(claims, expiresAt);
        state.tokens.put(token, result);
        return result;
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.strip();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).strip() : value;
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

    public static Integer getRoleIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        return getRoleIdFromToken(token);
    }

    private static Map<String, Object> payloadOf(String token) {
        try {
            VerifiedToken verified = verifiedToken(token);
            if (verified != null) {
                return verified.claims();
            }
        } catch (Exception ignored) {
            // Same failure as an invalid or expired token.
        }
        throw new UnauthorizedException("无效的token或token已过期");
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
     * @param key 签名密钥
     * @param encodedHeader  编码后的头部
     * @param encodedPayload 编码后的负载
     * @return 签名
     * @throws Exception 签名计算异常
     */
    private static String calculateSignature(SecretKeySpec key, String encodedHeader, String encodedPayload) throws Exception {
        String content = encodedHeader + "." + encodedPayload;
        Mac hmac = MAC_HOLDER.get();
        if (hmac == null) {
            hmac = Mac.getInstance(ALGORITHM);
            MAC_HOLDER.set(hmac);
        }
        hmac.init(key);
        byte[] signatureBytes = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }
} 
package com.admin.common.interceptor;


import com.admin.common.exception.UnauthorizedException;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * JWT拦截器，验证用户是否登录
 *
 * 除签名与过期时间外，这里还会校验 token 的版本号：
 * token 中携带签发时的 {@code token_version}，与数据库中用户当前的版本比对，
 * 不一致即判定失效。这样用户改密码、被管理员重置密码或被禁用后，
 * 此前签发的 token 会立即失效，而不必等 90 天自然过期。
 */
public class JwtInterceptor implements HandlerInterceptor {

    /**
     * 使用 Mapper 而非 Service，避免与 WebMvcConfig 形成循环依赖。
     * 加 @Lazy 是双保险：即使将来依赖链变化也不会在启动期报错。
     */
    @Autowired
    @Lazy
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("未登录或token已过期");
        }


        if (!JwtUtil.validateToken(token)) {
            throw new UnauthorizedException("无效的token或token已过期");
        }

        // 校验 token 版本，实现「踢下线」能力
        validateTokenVersion(token);

        return true;
    }

    /**
     * 比对 token 中的版本号与数据库中的当前版本号。
     */
    private void validateTokenVersion(String token) {
        Integer userId;
        Integer tokenVersion;
        try {
            userId = JwtUtil.getUserIdFromTokenAsInteger(token);
            tokenVersion = JwtUtil.getTokenVersionFromToken(token);
        } catch (Exception e) {
            // token 结构异常，统一按无效处理
            throw new UnauthorizedException("无效的token或token已过期");
        }

        if (userId == null) {
            throw new UnauthorizedException("无效的token或token已过期");
        }

        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("id", userId).last("LIMIT 1"));

        if (user == null) {
            // 用户已被删除
            throw new UnauthorizedException("账号不存在或已被删除");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new UnauthorizedException("账号已被停用");
        }

        // 历史 token 中没有 version 字段，按 0 处理；
        // 用户记录中该列为 null 时同样按 0 处理，保证升级后旧 token 依然可用
        // （真正需要吊销时版本会被写成 >=1，与新 token 保持一致）。
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        int inToken = tokenVersion == null ? 0 : tokenVersion;

        if (current != inToken) {
            throw new UnauthorizedException("登录状态已失效，请重新登录");
        }
    }
}

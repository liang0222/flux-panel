package com.admin.common.aop;

import cn.hutool.core.lang.Validator;
import com.admin.common.lang.R;
import com.admin.common.utils.IpUtils;
import com.admin.common.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

@Component
@Aspect
@Slf4j
public class LogAspect {

    @AfterReturning(pointcut = "@annotation(com.admin.common.aop.LogAnnotation)", returning = "returnValue")
    public void log(JoinPoint joinPoint, Object returnValue) {
        logRequest(joinPoint, "正常返回", returnValue instanceof R result ? result.getCode() : "-", "-");
    }

    @AfterThrowing(pointcut = "@annotation(com.admin.common.aop.LogAnnotation)", throwing = "error")
    public void recordLog(JoinPoint joinPoint, Throwable error) {
        logRequest(joinPoint, "异常退出", "-", error.getClass().getName());
    }

    private void logRequest(JoinPoint joinPoint, String outcome, Object code, String exceptionType) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }

        HttpServletRequest request = attributes.getRequest();
        HttpMethod requestMethod = HttpMethod.resolve(request.getMethod());
        String controllerMethod = joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName();

        // 仅记录白名单元数据。请求和响应数据可在任意字段、甚至自由文本中携带凭据。
        // 不读取参数、响应内容、URI 或异常消息，也不将异常对象交给日志框架。
        log.info("【请求日志】用户ID:[{}], IP地址:[{}], 请求方式:[{}], 控制器方法:[{}], 结果:[{}], 业务码:[{}], 异常类型:[{}]",
                userIdForLog(request), ipForLog(request), requestMethod == null ? "-" : requestMethod,
                controllerMethod, outcome, code, exceptionType);
    }

    private static Object userIdForLog(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        try {
            if (authorization != null && JwtUtil.validateToken(authorization)) {
                return JwtUtil.getUserIdFromToken(authorization);
            }
        } catch (RuntimeException ignored) {
            // 日志采集不应改变控制器结果，也不记录用于校验的认证材料。
        }
        return "未登录";
    }

    private static String ipForLog(HttpServletRequest request) {
        String ip = IpUtils.getIpAddr(request);
        if (ip == null || ip.length() > 45 || ip.indexOf('%') >= 0) {
            return "-";
        }
        return Validator.isIpv4(ip) || Validator.isIpv6(ip) ? ip : "-";
    }
}

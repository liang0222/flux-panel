package com.admin.common.aop;


import cn.hutool.core.util.ArrayUtil;
import com.admin.common.utils.AuditLogWriter;
import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson.JSON;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.IpUtils;
import com.admin.entity.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.CodeSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@Aspect
public class LogAspect {

    /** 需要脱敏的字段名关键字（小写包含匹配），避免明文密码/密钥进入审计表 */
    private static final String[] SENSITIVE_KEYS = {
            "pwd", "password", "secret", "token", "encryptkey", "appsecret", "webhook"
    };

    private static final String MASK = "******";

    /** 匹配 JSON 中的 "key":"value"，用于对序列化结果做脱敏 */
    private static final Pattern JSON_KV = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * 需要落库审计的写操作。
     * 查询类接口（list/get/check/package 等）只打日志、不落库，
     * 否则日志表会被读请求迅速淹没。
     */
    private static final Pattern WRITE_ACTION = Pattern.compile(
            "\\.(create|update|delete|remove|reset|assign|pause|resume|import|upload|force|changePassword|updatePassword|login)",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private AuditLogWriter auditLogWriter;

    @Pointcut("@annotation(com.admin.common.aop.LogAnnotation)")
    public void pt() {

    }

    /**
     * 返回后通知（@AfterReturning）：在某连接点（joinpoint）
     * 正常完成后执行的通知：例如一个方法没有抛出任何异常，正常返回
     * 方法执行完毕之后
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * crmAspect()指向需要控制的方法
     * returning  注解返回值
     *
     * @param joinPoint
     * @param returnValue 返回值
     * @throws Exception
     */
    @AfterReturning(value = "pt()", returning = "returnValue")
    public void log(JoinPoint joinPoint, Object returnValue) throws Throwable {
        // 获取请求信息
        HttpServletRequest request = HttpContextUtils.getHttpServletRequest();

        // 获取请求方法类型（POST/GET等）
        String requestMethod = request.getMethod();

        // 获取用户ID
        String authorization = request.getHeader("Authorization") + "";
        Object user_id = "未登录"; // 请求用户的id
        if (!authorization.equals("null")) {
            user_id = JwtUtil.getUserIdFromToken(authorization);
        }

        // 获取请求IP
        String ipAddr = IpUtils.getIpAddr(request);

        // 获取方法签名信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 获取控制器方法名
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = signature.getName();
        String controllerMethod = className + "." + methodName;


        // 获取请求参数
        String requestParams = getRequestParams(joinPoint);

        // 获取返回参数
        String responseParams = returnValue != null ? JSON.toJSONString(returnValue) : "无返回值";

        // 合并为一条完整的日志信息
        String logMessage = String.format(
            "【请求日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 返回参数:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, responseParams
        );

        // 打印单条完整日志
        log.info(logMessage);

        // 写操作落库审计（查询类跳过）
        persistAudit(user_id, ipAddr, requestMethod, controllerMethod, requestParams, responseParams);
    }

    /**
     * 把写操作持久化到审计表。
     *
     * 仅记录增删改类操作；写入是异步的，且内部吞掉异常，
     * 不会影响请求本身的成败。
     */
    private void persistAudit(Object userId, String ip, String httpMethod,
                              String controllerMethod, String requestParams, String responseParams) {
        try {
            if (!WRITE_ACTION.matcher(controllerMethod).find()) {
                return;
            }

            // 登录接口不记录参数（含明文密码），仅记录事件本身
            boolean isLogin = controllerMethod.endsWith(".login");

            AuditLog audit = new AuditLog();
            audit.setUserId(parseUserId(userId));
            audit.setUserName(extractUserName());
            audit.setIp(ip);
            audit.setMethod(httpMethod);
            audit.setAction(controllerMethod);
            audit.setParams(isLogin ? "(登录请求，参数已省略)" : maskSensitive(requestParams));
            audit.setSuccess(1);
            audit.setMessage(maskSensitive(summarize(responseParams)));
            audit.setCreatedTime(System.currentTimeMillis());

            auditLogWriter.write(audit);
        } catch (Exception e) {
            log.warn("构建审计日志失败（不影响业务）: {}", e.getMessage());
        }
    }

    /** 返回值只保留前 300 字符作为摘要，避免大响应体写进审计表 */
    private String summarize(String response) {
        if (response == null) {
            return null;
        }
        return response.length() > 300 ? response.substring(0, 300) + "..." : response;
    }

    private Integer parseUserId(Object userId) {
        if (userId == null) {
            return null;
        }
        try {
            return Integer.valueOf(userId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractUserName() {
        try {
            return JwtUtil.getNameFromToken();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对参数做敏感字段脱敏。
     *
     * 先按 JSON 的 key 处理序列化结果，再兜底处理 key=value 形式，
     * 确保密码、密钥、token 不会明文落到审计表。
     */
    static String maskSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;

        // 形如 "password":"xxx"
        Matcher m = JSON_KV.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            if (isSensitive(key)) {
                m.appendReplacement(sb, "\"" + key + "\":\"" + MASK + "\"");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        result = sb.toString();

        // 形如 password=xxx（cookie / 表单）
        for (String key : SENSITIVE_KEYS) {
            result = result.replaceAll("(?i)(" + key + "=)([^&\\s\",}]+)", "$1" + MASK);
        }
        return result;
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        for (String sensitive : SENSITIVE_KEYS) {
            if (lower.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 抛出异常后通知（@AfterThrowing）：方法抛出异常退出时执行的通知
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * throwing注解为错误信息
     *
     * @param joinPoint
     * @param ex
     */
    @AfterThrowing(value = "pt()", throwing = "ex")
    public void recordLog(JoinPoint joinPoint, Exception ex) {
        try {
            // 获取请求信息
            HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
            
            // 获取请求方法类型（POST/GET等）
            String requestMethod = request.getMethod();
            
            // 获取用户ID
            String authorization = request.getHeader("Authorization") + "";
            Object user_id = "未登录"; // 请求用户的id
            if (!authorization.equals("null")) {
                user_id = JwtUtil.getUserIdFromToken(authorization);
            }
            
            // 获取请求IP
            String ipAddr = IpUtils.getIpAddr(request);
            
            // 获取方法签名信息
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            
            // 获取控制器方法名
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = signature.getName();
            String controllerMethod = className + "." + methodName;
            

            
            // 获取请求参数
            String requestParams = getRequestParams(joinPoint);
            
            // 获取异常信息
            String exceptionMsg = ex != null ? ex.getMessage() : "未知异常";
            
            // 合并为一条完整的异常日志信息
            String errorMessage = String.format(
                "【异常日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 异常信息:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, exceptionMsg
            );
            
            // 打印单条完整异常日志
            log.info(errorMessage, ex);

            // 失败的写操作同样落库，便于排查「谁的操作报错了」
            persistFailureAudit(user_id, ipAddr, requestMethod, controllerMethod, requestParams, exceptionMsg);
        } catch (Exception e) {
            log.info("记录异常日志时出错: {}", e.getMessage());
        }
    }

    /** 记录一次失败的写操作 */
    private void persistFailureAudit(Object userId, String ip, String httpMethod,
                                     String controllerMethod, String requestParams, String errorMessage) {
        try {
            if (!WRITE_ACTION.matcher(controllerMethod).find()) {
                return;
            }
            boolean isLogin = controllerMethod.endsWith(".login");

            AuditLog audit = new AuditLog();
            audit.setUserId(parseUserId(userId));
            audit.setIp(ip);
            audit.setMethod(httpMethod);
            audit.setAction(controllerMethod);
            audit.setParams(isLogin ? "(登录请求，参数已省略)" : maskSensitive(requestParams));
            audit.setSuccess(0);
            audit.setMessage(maskSensitive(errorMessage));
            audit.setCreatedTime(System.currentTimeMillis());

            auditLogWriter.write(audit);
        } catch (Exception e) {
            log.warn("构建失败审计日志出错: {}", e.getMessage());
        }
    }
    
    /**
     * 获取请求参数
     */
    private String getRequestParams(JoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args.length == 0) {
                return "无参数";
            } else if (args[0] != null && args[0].toString().contains("SecurityContextHolderAwareRequestWrapper")) {
                return JSON.toJSONString(Arrays.toString(ArrayUtil.remove(args, 0)));
            } else {
                // 检查是否只有一个参数且已经是JSON字符串格式
                if (args.length == 1 && args[0] != null) {
                    // 如果参数本身就是字符串且是JSON格式，直接返回
                    if (args[0] instanceof String && ((String) args[0]).startsWith("{") && ((String) args[0]).endsWith("}")) {
                        return (String) args[0];
                    }
                    
                    // 如果参数是普通对象，直接序列化
                    try {
                        return JSON.toJSONString(args[0]);
                    } catch (Exception e) {
                        // 如果序列化失败，再尝试使用参数名映射
                        Map<String, Object> map = new HashMap<>();
                        String[] names = ((CodeSignature) joinPoint.getSignature()).getParameterNames();
                        if (names != null) {
                            map.put(names[0], args[0]);
                            return JSON.toJSONString(map);
                        }
                        return JSON.toJSONString(args[0]);
                    }
                } else {
                    // 多个参数时，使用参数名映射
                    Map<String, Object> map = new HashMap<>();
                    String[] names = ((CodeSignature) joinPoint.getSignature()).getParameterNames();
                    if (names != null) {
                        for (int i = 0; i < names.length; i++) {
                            map.put(names[i], args[i]);
                        }
                    }
                    return JSON.toJSONString(map);
                }
            }
        } catch (Exception e) {
            return "获取参数失败: " + e.getMessage();
        }
    }
}

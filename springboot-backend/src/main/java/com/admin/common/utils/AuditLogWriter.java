package com.admin.common.utils;

import com.admin.entity.AuditLog;
import com.admin.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 审计日志写入器
 *
 * <p>单独抽成一个 Bean 的原因：{@code @Async} 依赖 Spring 代理生效，
 * 同类内部的方法调用不会走代理，异步将退化为同步。
 *
 * <p>写入失败只记日志、绝不上抛：审计是旁路能力，
 * 不能因为它写不进去而让正常的业务操作失败。
 */
@Slf4j
@Component
public class AuditLogWriter {

    /** 单条参数的最大长度，避免超大请求体把日志表撑爆 */
    private static final int MAX_PARAM_LENGTH = 2000;

    @Resource
    private AuditLogMapper auditLogMapper;

    @Async
    public void write(AuditLog auditLog) {
        if (auditLog == null) {
            return;
        }
        try {
            if (auditLog.getParams() != null && auditLog.getParams().length() > MAX_PARAM_LENGTH) {
                auditLog.setParams(auditLog.getParams().substring(0, MAX_PARAM_LENGTH) + "...(已截断)");
            }
            if (auditLog.getMessage() != null && auditLog.getMessage().length() > MAX_PARAM_LENGTH) {
                auditLog.setMessage(auditLog.getMessage().substring(0, MAX_PARAM_LENGTH) + "...(已截断)");
            }
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响业务）: {}", e.getMessage());
        }
    }
}

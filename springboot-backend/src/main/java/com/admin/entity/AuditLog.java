package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
 * 操作审计日志
 *
 * <p>背景：原实现的 {@code LogAspect} 只把请求信息 {@code log.info} 到文件，
 * 面板重启或日志轮转后无法追溯「谁在什么时候删了哪条转发 / 改了谁的流量」。
 * 本表把「写操作」持久化，便于事后追责与排查。
 *
 * <p>仅记录增删改类操作（查询类不落库），避免日志表膨胀。
 */
@Data
public class AuditLog {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作者用户ID（未登录时为 null）。与 Forward.userId 保持一致，使用 Integer。 */
    private Integer userId;

    /** 操作者用户名 */
    private String userName;

    /** 请求来源 IP */
    private String ip;

    /** HTTP 方法（POST 等） */
    private String method;

    /** 控制器与方法名，例如 com.admin.controller.ForwardController.delete */
    private String action;

    /** 请求参数（已做长度截断与敏感字段脱敏） */
    private String params;

    /** 执行结果：1 成功，0 失败 */
    private Integer success;

    /** 失败原因或返回摘要 */
    private String message;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 发生时间（毫秒时间戳） */
    private Long createdTime;

}

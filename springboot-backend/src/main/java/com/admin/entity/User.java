package com.admin.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class User extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 创建时间（时间戳）
     */
    private Long createdTime;

    /**
     * 更新时间（时间戳）
     */
    private Long updatedTime;

    /**
     * 状态（0：正常，1：删除）
     */
    private Integer status;

    private String user;

    private String pwd;

    private Integer roleId;

    private Long expTime;

    private Long flow;

    private Long inFlow;

    private Long outFlow;

    private Integer num;

    private Long flowResetTime;

    /**
     * token 版本号。
     *
     * 签发的 JWT 中会带上该值，校验时与库中比对：
     * 一旦密码被修改或账号被管理员重置，就把该值 +1，
     * 使此前签发的所有 token 立即失效（原实现中 token 有效期 90 天且无法吊销）。
     */
    private Integer tokenVersion;

    /** 连续登录失败次数，登录成功后清零 */
    private Integer loginFailCount;

    /** 账号锁定截止时间（毫秒时间戳，0 或 null 表示未锁定） */
    private Long lockedUntil;


}

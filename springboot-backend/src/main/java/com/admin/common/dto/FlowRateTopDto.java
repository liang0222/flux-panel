package com.admin.common.dto;

import lombok.Data;

/**
 * <p>
 * 实时速率排行条目（最忙端口 / 最高用户）
 * </p>
 *
 * @since 2.0.7-beta
 */
@Data
public class FlowRateTopDto {

    /**
     * 转发ID（排行条目为转发时存在）
     */
    private Long forwardId;

    /**
     * 转发名称
     */
    private String forwardName;

    /**
     * 入口端口
     */
    private Integer port;

    /**
     * 用户ID
     */
    private Integer userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 总速率（字节/秒）
     */
    private Long totalRate;

    /**
     * 入站速率（字节/秒）
     */
    private Long inRate;

    /**
     * 出站速率（字节/秒）
     */
    private Long outRate;

}

package com.admin.common.dto;

import lombok.Data;

/**
 * 端口流量排行榜条目
 *
 * @since 2025-09-28
 */
@Data
public class PortRankingDto {

    /** 排名序号，从 1 开始，由服务层回填 */
    private Integer rank;

    /** 转发ID */
    private Long forwardId;

    /** 转发名称 */
    private String name;

    /** 入口端口 */
    private Integer inPort;

    /** 出口端口 */
    private Integer outPort;

    /** 转发所属用户ID */
    private Integer userId;

    /** 转发所属用户名 */
    private String userName;

    /** 隧道ID */
    private Integer tunnelId;

    /** 隧道名称 */
    private String tunnelName;

    /** 统计区间内的总流量（字节） */
    private Long flow;

    /** 转发当前状态：1 启用，0 暂停 */
    private Integer status;

    /**
     * 兼容字段：统计区间内的入向流量。
     * 快照表按 forward 维度合并了入/出向，这里保持与入向一致，
     * 便于前端沿用 inFlow/outFlow 的展示习惯。
     */
    private Long inFlow;

    /** 兼容字段：统计区间内的出向流量 */
    private Long outFlow;

}

package com.admin.common.dto;

import lombok.Data;

import java.util.List;

/**
 * <p>
 * 端口转发流量排行榜条目
 * </p>
 * 一个条目对应一条端口转发规则（gost 服务按转发维度统计流量），
 * 展示其入口端口标识与所选时间范围内的累计转发流量。
 *
 * @since 2.0.7-beta
 */
@Data
public class ForwardFlowRankItemDto {

    /**
     * 排名（从 1 开始，按总流量降序）
     */
    private Integer rank;

    /**
     * 转发ID
     */
    private Long forwardId;

    /**
     * 转发名称（转发已删除时为最后一次采集时的名称快照）
     */
    private String forwardName;

    /**
     * 入口端口（主端口，转发已删除时为最后一次采集时的端口快照）
     */
    private Integer port;

    /**
     * 全部入口端口
     */
    private List<Integer> ports;

    /**
     * 入口节点ID
     */
    private Long nodeId;

    /**
     * 隧道ID
     */
    private Integer tunnelId;

    /**
     * 隧道名称
     */
    private String tunnelName;

    /**
     * 所属用户ID（0 表示管理员直连转发）
     */
    private Integer userId;

    /**
     * 所属用户名
     */
    private String userName;

    /**
     * 区间内新增入站流量（字节）
     */
    private Long inFlow;

    /**
     * 区间内新增出站流量（字节）
     */
    private Long outFlow;

    /**
     * 区间内新增总流量（入站 + 出站，字节）
     */
    private Long totalFlow;

    /**
     * 区间内采集点数量
     */
    private Integer sampleCount;

    /**
     * 区间内首次采集时间
     */
    private Long firstTime;

    /**
     * 区间内最后一次采集时间
     */
    private Long lastTime;

    /**
     * 转发是否已被删除（历史数据仍保留）
     */
    private Boolean deleted;

    /**
     * 转发当前状态（1 正常，0 暂停；转发已删除时为 null）
     */
    private Integer status;

}

package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
 * <p>
 * 端口转发流量采集记录
 * </p>
 * 每 5 分钟对每条端口转发采集一次 {@link Forward} 上累计的 in_flow / out_flow 计数器快照，
 * 并用相邻两次采集的差额计算本区间新增流量，保证服务重启后历史仍可查询。
 *
 * @since 2.0.7-beta
 */
@Data
public class ForwardFlowStatistics {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 转发记录ID（端口转发规则）
     */
    private Long forwardId;

    /**
     * 采集时的转发名称快照（转发被删除后仍可用于展示）
     */
    private String forwardName;

    /**
     * 采集时的入口端口快照（主端口）
     */
    private Integer port;

    /**
     * 采集时的全部入口端口快照，多个端口以英文逗号分隔
     */
    private String ports;

    /**
     * 采集时的入口节点ID快照
     */
    private Long nodeId;

    /**
     * 采集时的隧道ID快照
     */
    private Integer tunnelId;

    /**
     * 采集时的用户ID快照
     */
    private Integer userId;

    /**
     * 采集时的用户名快照
     */
    private String userName;

    /**
     * 采集时的累计入站流量（转发计数器，已按隧道倍率与计费方式换算）
     */
    private Long inFlow;

    /**
     * 采集时的累计出站流量（转发计数器）
     */
    private Long outFlow;

    /**
     * 本次采集新增入站流量（相邻采集差额，非负）
     */
    private Long inFlowDelta;

    /**
     * 本次采集新增出站流量（相邻采集差额，非负）
     */
    private Long outFlowDelta;

    /**
     * 本次采集新增总流量 = inFlowDelta + outFlowDelta
     */
    private Long flow;

    /**
     * 采集时的累计总流量 = inFlow + outFlow，用于差额计算与重启恢复
     */
    private Long totalFlow;

    /**
     * 计数器是否发生回退/重置（1 表示本次按重置处理）
     */
    private Integer resetFlag;

    /**
     * 采集时间（毫秒时间戳）
     */
    private Long createdTime;

    /**
     * 采集时间（yyyy-MM-dd HH:mm，便于人工排查）
     */
    private String time;

}

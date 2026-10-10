package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
 * <p>
 * 转发（端口）级别的小时流量快照
 * </p>
 * 与 statistics_flow（用户级别）互补：statistics_flow 只能回答「哪个用户用了多少」，
 * 本表带 forward_id / in_port 维度，用于「端口流量排行榜（今天/三天/七天）」。
 *
 * 说明：forward.in_flow / out_flow 是累计计数器且没有时间戳，无法直接推导时间段用量，
 * 因此这里沿用 statistics_flow 已有的「累计值差值」思路，每小时记录一次增量。
 *
 * @since 2025-09-28
 */
@Data
public class StatisticsFlowForward {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 转发ID（forward.id） */
    private Long forwardId;

    /** 转发所属用户ID */
    private Integer userId;

    /** 转发名称，冗余存储，避免转发被删除后排行榜无法展示 */
    private String name;

    /** 入口端口（forward.in_port） */
    private Integer inPort;

    /** 出口端口（forward.out_port） */
    private Integer outPort;

    /** 隧道ID */
    private Integer tunnelId;

    /** 本采样周期内的增量流量（字节，已含倍率与单双向计费） */
    private Long flow;

    /** 采样时刻的累计流量（字节），用于下一次计算增量 */
    private Long totalFlow;

    /** 采样所在小时的展示字符串，如 "14:00" */
    private String time;

    /** 采样时刻毫秒时间戳，聚合查询按此字段做范围过滤 */
    private Long createdTime;

}

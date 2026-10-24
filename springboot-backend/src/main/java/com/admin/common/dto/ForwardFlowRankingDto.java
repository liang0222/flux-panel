package com.admin.common.dto;

import lombok.Data;

import java.util.List;

/**
 * <p>
 * 端口转发流量排行榜
 * </p>
 * 统计口径：总流量 = 入站流量 + 出站流量，数据来自 forward.in_flow / forward.out_flow
 * 计数器（已按隧道倍率与单向/双向计费方式换算），按 5 分钟采集的增量累加。
 *
 * @since 2.0.7-beta
 */
@Data
public class ForwardFlowRankingDto {

    /**
     * 统计范围标识：today / 3d / 7d / 30d
     */
    private String range;

    /**
     * 统计范围名称：今天 / 近 3 天 / 近 7 天 / 近 30 天
     */
    private String rangeLabel;

    /**
     * 统计范围包含的自然日天数（含今天）
     */
    private Integer days;

    /**
     * 统计区间开始时间（毫秒时间戳，含）
     */
    private Long startTime;

    /**
     * 统计区间结束时间（毫秒时间戳，含）
     */
    private Long endTime;

    /**
     * 统计区间开始时间文本
     */
    private String startTimeText;

    /**
     * 统计区间结束时间文本
     */
    private String endTimeText;

    /**
     * 采集间隔（分钟）
     */
    private Integer sampleIntervalMinutes;

    /**
     * 历史数据保留天数
     */
    private Integer retentionDays;

    /**
     * 流量方向口径标识
     */
    private String trafficScope;

    /**
     * 流量方向口径说明
     */
    private String trafficScopeLabel;

    /**
     * 排行榜条目（按总流量降序）
     */
    private List<ForwardFlowRankItemDto> items;

    /**
     * 排行榜涉及的转发数量
     */
    private Integer forwardCount;

    /**
     * 排行榜涉及的去重入口端口数量
     */
    private Integer portCount;

    /**
     * 所有端口区间内总流量（入站 + 出站）
     */
    private Long totalFlow;

    /**
     * 所有端口区间内新增入站流量
     */
    private Long totalInFlow;

    /**
     * 所有端口区间内新增出站流量
     */
    private Long totalOutFlow;

    /**
     * 最近一次采集时间
     */
    private Long lastCollectTime;

    /**
     * 最近一次采集时间文本
     */
    private String lastCollectTimeText;

    /**
     * 生成时间
     */
    private Long generatedTime;

}

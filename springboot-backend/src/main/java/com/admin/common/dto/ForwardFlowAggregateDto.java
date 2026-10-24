package com.admin.common.dto;

import lombok.Data;

/**
 * <p>
 * 端口转发流量区间聚合结果
 * </p>
 * 由 forward_flow_statistics 按 forward_id 聚合得到，流量单位与转发计数器一致（字节，
 * 已在上报时按隧道倍率与计费方式换算）。
 *
 * @since 2.0.7-beta
 */
@Data
public class ForwardFlowAggregateDto {

    /**
     * 转发ID
     */
    private Long forwardId;

    /**
     * 区间内新增入站流量
     */
    private Long inFlow;

    /**
     * 区间内新增出站流量
     */
    private Long outFlow;

    /**
     * 区间内新增总流量（入站 + 出站）
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

}

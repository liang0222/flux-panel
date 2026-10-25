package com.admin.common.dto;

import lombok.Data;

import java.util.List;

/**
 * <p>
 * 实时速率
 * </p>
 * 数据来源：节点每 5 秒上报的服务增量，在内存中按 10 秒滑动窗口换算，不落库。
 * 口径：入站 + 出站，上报值已按隧道倍率与单向/双向计费换算。
 *
 * @since 2.0.7-beta
 */
@Data
public class FlowRateDto {

    /**
     * 统计窗口（秒）
     */
    private Integer windowSeconds;

    /**
     * 最近一次收到上报的时间
     */
    private Long sampleTime;

    /**
     * 数据范围：all（管理员，全部用户）/ self（普通用户，仅自己）
     */
    private String scope;

    /**
     * 总速率（字节/秒，入站 + 出站）
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

    /**
     * 入站占比（%）
     */
    private Double inPercent;

    /**
     * 出站占比（%）
     */
    private Double outPercent;

    /**
     * 窗口内有流量的转发数量
     */
    private Integer activeForwardCount;

    /**
     * 窗口内有流量的用户数量
     */
    private Integer activeUserCount;

    /**
     * 最忙端口（等于 topForwards 的第一项，保留以兼容旧前端）
     */
    private FlowRateTopDto topForward;

    /**
     * 速率最高的若干端口，按总速率降序，最多 {@code FlowRateService.TOP_FORWARD_LIMIT} 条
     */
    private List<FlowRateTopDto> topForwards;

    /**
     * 最高用户（仅管理员范围返回）
     */
    private FlowRateTopDto topUser;

}

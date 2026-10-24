package com.admin.service;

import com.admin.common.dto.ForwardFlowRankingDto;
import com.admin.common.utils.FlowStatisticsRange;
import com.admin.entity.ForwardFlowStatistics;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.ZoneId;

/**
 * <p>
 * 端口转发流量统计服务
 * </p>
 * 负责每 5 分钟的转发流量采集入库、按时间范围聚合排行以及历史数据保留。
 *
 * @since 2.0.7-beta
 */
public interface ForwardFlowStatisticsService extends IService<ForwardFlowStatistics> {

    /**
     * 采集间隔（分钟）
     */
    int SAMPLE_INTERVAL_MINUTES = 5;

    /**
     * 历史数据保留天数（满足至少保留 30 天的要求，额外多留 1 天覆盖时区边界）
     */
    int RETENTION_DAYS = 31;

    /**
     * 采集一次所有端口转发的流量并入库
     *
     * @return 本次写入的采集记录条数
     */
    int collectStatistics();

    /**
     * 查询端口流量排行榜
     *
     * @param range 统计范围
     * @return 排行榜
     */
    ForwardFlowRankingDto getRanking(FlowStatisticsRange range);

    /**
     * 查询端口流量排行榜（指定当前时间与时区，便于测试）
     *
     * @param range     统计范围
     * @param nowMillis 当前时间戳（毫秒）
     * @param zone      时区
     * @return 排行榜
     */
    ForwardFlowRankingDto getRanking(FlowStatisticsRange range, long nowMillis, ZoneId zone);

    /**
     * 清理超过保留期的采集记录
     *
     * @param retentionDays 保留天数
     * @return 删除条数
     */
    int cleanupExpiredStatistics(int retentionDays);

    /**
     * 最近一次采集时间
     *
     * @return 毫秒时间戳，无记录时返回 null
     */
    Long getLastCollectTime();

}

package com.admin.common.task;

import com.admin.service.ForwardFlowStatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;

/**
 * 端口转发流量采集定时任务
 * <p>
 * 1. 每 5 分钟采集一次所有端口转发的累计流量计数器，按相邻采集差额写入 forward_flow_statistics；
 * 2. 每天凌晨清理超过保留期（31 天）的历史采集记录，保证至少保留 30 天。
 *
 * @since 2.0.7-beta
 */
@Slf4j
@Configuration
@EnableScheduling
public class ForwardFlowStatisticsAsync {

    @Resource
    ForwardFlowStatisticsService forwardFlowStatisticsService;

    /**
     * 每 5 分钟采集一次端口转发流量
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void collect_forward_flow() {
        try {
            forwardFlowStatisticsService.collectStatistics();
        } catch (Exception e) {
            log.error("端口流量采集任务执行失败", e);
        }
    }

    /**
     * 每天 03:10 清理过期采集记录（保留 31 天，满足至少 30 天的查询需求）
     */
    @Scheduled(cron = "0 10 3 * * ?")
    public void cleanup_forward_flow() {
        try {
            forwardFlowStatisticsService.cleanupExpiredStatistics(ForwardFlowStatisticsService.RETENTION_DAYS);
        } catch (Exception e) {
            log.error("端口流量历史数据清理任务执行失败", e);
        }
    }

}

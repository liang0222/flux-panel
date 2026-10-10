package com.admin.common.task;


import com.admin.entity.Forward;
import com.admin.entity.StatisticsFlow;
import com.admin.entity.StatisticsFlowForward;
import com.admin.entity.User;
import com.admin.service.ForwardService;
import com.admin.service.StatisticsFlowForwardService;
import com.admin.service.StatisticsFlowService;
import com.admin.service.UserService;
import com.admin.mapper.StatisticsFlowForwardMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Configuration
@EnableScheduling
public class StatisticsFlowAsync {

    /** 用户级快照保留 48 小时（维持原有行为，仅用于 24 小时折线图） */
    private static final long USER_SNAPSHOT_RETENTION_MS = 48L * 60 * 60 * 1000;

    /**
     * 端口级快照保留 8 天。
     * 排行榜需要支持「七天」视图（含今天，即最近 7 个自然日），
     * 原先的 48 小时保留期不足以支撑，故单独放宽到 8 天留出余量。
     */
    private static final long FORWARD_SNAPSHOT_RETENTION_MS = 8L * 24 * 60 * 60 * 1000;

    @Resource
    UserService userService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Resource
    ForwardService forwardService;

    @Resource
    StatisticsFlowForwardService statisticsFlowForwardService;

    @Resource
    StatisticsFlowForwardMapper statisticsFlowForwardMapper;

    @Scheduled(cron = "0 0 * * * ?")
    public void statistics_flow() {
        LocalDateTime currentHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        String hourString = currentHour.format(DateTimeFormatter.ofPattern("HH:mm"));
        long time = new Date().getTime();

        // 删除48小时前的数据
        long nowMs = new Date().getTime();
        long cutoffMs = nowMs - USER_SNAPSHOT_RETENTION_MS;
        statisticsFlowService.remove(
                new LambdaQueryWrapper<StatisticsFlow>()
                        .lt(StatisticsFlow::getCreatedTime, cutoffMs)
        );

        List<User> list = userService.list();
        List<StatisticsFlow> statisticsFlowList = new ArrayList<>();

        for (User user : list) {
            long currentFlow = user.getInFlow() + user.getOutFlow();

            // 从数据库获取上一次记录
            StatisticsFlow lastFlowRecord = statisticsFlowService.getOne(
                    new LambdaQueryWrapper<StatisticsFlow>()
                            .eq(StatisticsFlow::getUserId, user.getId()) 
                            .orderByDesc(StatisticsFlow::getId)         
                            .last("LIMIT 1")                     
            );

            long currentTotalFlow = currentFlow;
            long incrementFlow = currentTotalFlow;
            
            if (lastFlowRecord != null) {
                long lastTotalFlow = lastFlowRecord.getTotalFlow();
                incrementFlow = currentTotalFlow - lastTotalFlow;
                
                if (incrementFlow < 0) {
                    incrementFlow = currentTotalFlow; 
                }
            }

            StatisticsFlow statisticsFlow = new StatisticsFlow();
            statisticsFlow.setUserId(user.getId());
            statisticsFlow.setFlow(incrementFlow);        
            statisticsFlow.setTotalFlow(currentTotalFlow); 
            statisticsFlow.setTime(hourString);
            statisticsFlow.setCreatedTime(time);

            statisticsFlowList.add(statisticsFlow);
        }

        statisticsFlowService.saveBatch(statisticsFlowList);
    }

    /**
     * 采集转发（端口）级别的流量快照，供「端口流量排行榜」使用。
     *
     * 这里刻意使用独立的 @Scheduled 方法，而不是挂在 statistics_flow() 末尾：
     * 两个统计面向不同的表、不同的保留期，拆开后任何一侧出错都不会拖累另一侧。
     *
     * 与用户级逻辑一致：用「当前累计值 - 上次快照累计值」得到本周期增量。
     * 累计值在某些情况下会变小（用户重置流量、转发被重建等），此时按「本次全部计入」
     * 处理，与 statistics_flow 的既有做法保持一致，避免出现负流量。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void statistics_forward_flow() {
        String hourString = LocalDateTime.now()
                .withMinute(0).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        long time = new Date().getTime();
        long nowMs = time;

        try {
            // 清理超出保留期的端口快照
            long forwardCutoffMs = nowMs - FORWARD_SNAPSHOT_RETENTION_MS;
            statisticsFlowForwardService.remove(
                    new LambdaQueryWrapper<StatisticsFlowForward>()
                            .lt(StatisticsFlowForward::getCreatedTime, forwardCutoffMs)
            );

            List<Forward> forwards = forwardService.list();
            if (forwards == null || forwards.isEmpty()) {
                return;
            }

            List<StatisticsFlowForward> snapshotList = new ArrayList<>();
            for (Forward forward : forwards) {
                if (forward == null || forward.getId() == null) {
                    continue;
                }

                long inFlow = forward.getInFlow() == null ? 0L : forward.getInFlow();
                long outFlow = forward.getOutFlow() == null ? 0L : forward.getOutFlow();
                long currentTotalFlow = inFlow + outFlow;

                StatisticsFlowForward last = statisticsFlowForwardMapper
                        .selectLatestByForwardId(forward.getId());

                long incrementFlow = currentTotalFlow;
                if (last != null && last.getTotalFlow() != null) {
                    incrementFlow = currentTotalFlow - last.getTotalFlow();
                    if (incrementFlow < 0) {
                        incrementFlow = currentTotalFlow;
                    }
                }

                StatisticsFlowForward snapshot = new StatisticsFlowForward();
                snapshot.setForwardId(forward.getId());
                snapshot.setUserId(forward.getUserId());
                snapshot.setName(forward.getName());
                snapshot.setInPort(forward.getInPort());
                snapshot.setOutPort(forward.getOutPort());
                snapshot.setTunnelId(forward.getTunnelId());
                snapshot.setFlow(incrementFlow);
                snapshot.setTotalFlow(currentTotalFlow);
                snapshot.setTime(hourString);
                snapshot.setCreatedTime(time);

                snapshotList.add(snapshot);
            }

            if (!snapshotList.isEmpty()) {
                statisticsFlowForwardService.saveBatch(snapshotList);
            }
        } catch (Exception e) {
            // 排行榜采集失败不应影响原有的用户级统计
            log.error("端口流量快照采集失败: {}", e.getMessage(), e);
        }
    }

}

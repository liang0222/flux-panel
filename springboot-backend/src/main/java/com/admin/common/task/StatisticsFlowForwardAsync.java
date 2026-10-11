package com.admin.common.task;

import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.StatisticsFlowForward;
import com.admin.mapper.StatisticsFlowForwardMapper;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.StatisticsFlowForwardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 端口（转发）级流量采集，供「端口流量排行榜」使用。
 *
 * <p><b>采集频率：每 5 分钟一次</b>（cron 分钟位为 {@code *&#47;5}）。
 *
 * <p>为什么单独一个类、单独一个 @Scheduled 方法：
 * 原版的 {@code StatisticsFlowAsync} 负责用户级流量快照（保留 48 小时）。
 * 排行榜面向「转发（端口）」维度，保留期与查询方式都不同。
 * 拆成独立的类后，任何一侧出错或调频都不会影响另一侧，
 * 同时避免改动原版文件 —— <b>原版功能保持一行不动</b>。
 *
 * <p>增量计算方式与原版用户级统计保持一致：
 * 「本次累计值 - 上次快照累计值」；若为负（用户重置流量、转发被重建等），
 * 按「本次全部计入」处理，避免出现负流量。
 */
@Slf4j
@Configuration
public class StatisticsFlowForwardAsync {

    /**
     * 数据保留期：30 天。
     * 排行榜最大时间视图就是 30 天，超过该范围的数据不再需要，定时清理以免无限增长。
     */
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    /** 单次采集最多处理的转发数，防御性上限，避免异常情况下一次性载入过多数据 */
    private static final int BATCH_SIZE = 2000;

    @Resource
    private ForwardService forwardService;

    @Resource
    private ForwardPortService forwardPortService;

    @Resource
    private StatisticsFlowForwardService statisticsFlowForwardService;

    @Resource
    private StatisticsFlowForwardMapper statisticsFlowForwardMapper;

    /**
     * 每 5 分钟采集一次所有转发的流量增量。
     *
     * <p>cron 说明：{@code 0 *&#47;5 * * * ?} 表示「秒=0、分钟为 5 的倍数」，
     * 即每小时的 00、05、10 …… 55 分触发。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void statistics_forward_flow() {
        long now = new Date().getTime();

        try {
            // 1. 清理超出 30 天的历史数据
            statisticsFlowForwardService.remove(
                    new LambdaQueryWrapper<StatisticsFlowForward>()
                            .lt(StatisticsFlowForward::getCreatedTime, now - RETENTION_MS)
            );

            // 2. 取全部转发（含已暂停的：暂停期间流量为 0，仍写入快照，
            //    这样排行榜上能看出「该转发存在但当前无流量」）
            List<Forward> forwards = forwardService.list();
            if (forwards == null || forwards.isEmpty()) {
                return;
            }
            if (forwards.size() > BATCH_SIZE) {
                log.warn("转发数量 {} 超过单次采集上限 {}，本次只处理前 {} 条",
                        forwards.size(), BATCH_SIZE, BATCH_SIZE);
                forwards = forwards.subList(0, BATCH_SIZE);
            }

            // 3. 一次性取出所有转发的入口端口（beta 版端口存在 forward_port 表）
            Map<Long, Integer> inPortMap = loadInPortMap();

            List<StatisticsFlowForward> snapshots = new ArrayList<>(forwards.size());

            for (Forward forward : forwards) {
                if (forward == null || forward.getId() == null) {
                    continue;
                }

                long inFlow = forward.getInFlow() == null ? 0L : forward.getInFlow();
                long outFlow = forward.getOutFlow() == null ? 0L : forward.getOutFlow();
                long currentTotal = inFlow + outFlow;

                StatisticsFlowForward last =
                        statisticsFlowForwardMapper.selectLatestByForwardId(forward.getId());

                long increment = currentTotal;
                if (last != null && last.getTotalFlow() != null) {
                    increment = currentTotal - last.getTotalFlow();
                    if (increment < 0) {
                        // 累计值变小：用户重置流量或转发被重建，按「本次全部计入」处理
                        increment = currentTotal;
                    }
                }

                StatisticsFlowForward snapshot = new StatisticsFlowForward();
                snapshot.setForwardId(forward.getId());
                snapshot.setUserId(forward.getUserId());
                snapshot.setName(forward.getName());
                snapshot.setTunnelId(forward.getTunnelId());
                snapshot.setFlow(increment);
                snapshot.setTotalFlow(currentTotal);
                snapshot.setCreatedTime(now);
                snapshot.setInPort(inPortMap.get(forward.getId()));
                snapshot.setRemoteAddr(forward.getRemoteAddr());

                snapshots.add(snapshot);
            }

            if (!snapshots.isEmpty()) {
                statisticsFlowForwardService.saveBatch(snapshots);
            }
        } catch (Exception e) {
            // 排行榜采集失败不应影响原版任何功能
            log.error("端口流量快照采集失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 一次性载入「转发ID -> 入口端口」映射。
     *
     * <p>beta 版把 in_port 从 forward 表移到了 forward_port 表，
     * 这里整表读一次再在内存里查，避免在循环里逐条查询。
     * 取不到端口不影响流量统计本身。
     */
    private Map<Long, Integer> loadInPortMap() {
        Map<Long, Integer> map = new HashMap<>();
        try {
            List<ForwardPort> ports = forwardPortService.list();
            if (ports != null) {
                for (ForwardPort fp : ports) {
                    if (fp != null && fp.getForwardId() != null && fp.getPort() != null) {
                        map.put(fp.getForwardId(), fp.getPort());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取转发端口映射失败，本次快照将不含入口端口: {}", e.getMessage());
        }
        return map;
    }
}

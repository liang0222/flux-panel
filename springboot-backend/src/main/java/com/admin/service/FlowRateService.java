package com.admin.service;

import com.admin.common.dto.FlowRateDto;
import com.admin.common.dto.FlowRateTopDto;
import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * 实时速率服务
 * </p>
 * 把 {@link RealtimeFlowTracker} 的滑动窗口结果组装成界面需要的汇总与 Top 信息。
 *
 * @since 2.0.7-beta
 */
@Service
public class FlowRateService {

    /**
     * 端口速率排行展示条数
     */
    public static final int TOP_FORWARD_LIMIT = 10;

    private final RealtimeFlowTracker realtimeFlowTracker;

    private final ForwardService forwardService;

    private final ForwardPortService forwardPortService;

    public FlowRateService(RealtimeFlowTracker realtimeFlowTracker,
                           ForwardService forwardService,
                           ForwardPortService forwardPortService) {
        this.realtimeFlowTracker = realtimeFlowTracker;
        this.forwardService = forwardService;
        this.forwardPortService = forwardPortService;
    }

    /**
     * 查询当前实时速率
     *
     * @param userId 当前用户ID
     * @param admin  是否管理员（管理员看全部，普通用户只看自己）
     * @return 实时速率汇总
     */
    public FlowRateDto getCurrentRate(Integer userId, boolean admin) {
        List<RealtimeFlowTracker.ForwardRate> rates = realtimeFlowTracker.currentRates(admin ? null : userId);

        long inRate = 0L;
        long outRate = 0L;
        Set<Integer> activeUsers = new HashSet<>();
        for (RealtimeFlowTracker.ForwardRate rate : rates) {
            inRate += rate.getInRate();
            outRate += rate.getOutRate();
            if (rate.getUserId() != null) {
                activeUsers.add(rate.getUserId());
            }
        }
        long totalRate = inRate + outRate;

        FlowRateDto dto = new FlowRateDto();
        dto.setWindowSeconds(RealtimeFlowTracker.WINDOW_SECONDS);
        dto.setSampleTime(realtimeFlowTracker.lastReportTime());
        dto.setScope(admin ? "all" : "self");
        dto.setInRate(inRate);
        dto.setOutRate(outRate);
        dto.setTotalRate(totalRate);
        dto.setInPercent(percent(inRate, totalRate));
        dto.setOutPercent(percent(outRate, totalRate));
        dto.setActiveForwardCount(rates.size());
        dto.setActiveUserCount(activeUsers.size());

        List<FlowRateTopDto> topForwards = buildTopForwards(rates);
        dto.setTopForwards(topForwards);
        if (!topForwards.isEmpty()) {
            dto.setTopForward(topForwards.get(0));
        }
        if (admin) {
            dto.setTopUser(buildTopUser(rates));
        }
        return dto;
    }

    /**
     * 速率最高的若干个端口：按总速率降序取前 {@link #TOP_FORWARD_LIMIT} 条，
     * 转发与端口信息各用一次批量查询补全，避免轮询接口产生 N+1 查询。
     * 转发已删除时名称回退为「转发#ID」，端口为空。
     */
    private List<FlowRateTopDto> buildTopForwards(List<RealtimeFlowTracker.ForwardRate> rates) {
        if (rates.isEmpty()) {
            return new ArrayList<>();
        }
        List<RealtimeFlowTracker.ForwardRate> limited = rates.size() > TOP_FORWARD_LIMIT
                ? rates.subList(0, TOP_FORWARD_LIMIT)
                : rates;

        List<Long> forwardIds = new ArrayList<>(limited.size());
        for (RealtimeFlowTracker.ForwardRate rate : limited) {
            forwardIds.add(rate.getForwardId());
        }

        Map<Long, String> forwardNames = new HashMap<>();
        for (Forward forward : forwardService.listByIds(forwardIds)) {
            forwardNames.put(forward.getId(), forward.getName());
        }

        Map<Long, Integer> forwardPorts = new HashMap<>();
        List<ForwardPort> ports = forwardPortService.list(
                new QueryWrapper<ForwardPort>()
                        .in("forward_id", forwardIds)
                        .orderByAsc("id")
        );
        for (ForwardPort port : ports) {
            forwardPorts.putIfAbsent(port.getForwardId(), port.getPort());
        }

        List<FlowRateTopDto> topForwards = new ArrayList<>(limited.size());
        for (RealtimeFlowTracker.ForwardRate rate : limited) {
            FlowRateTopDto top = new FlowRateTopDto();
            top.setForwardId(rate.getForwardId());
            top.setUserId(rate.getUserId());
            top.setUserName(rate.getUserName());
            top.setTotalRate(rate.getTotalRate());
            top.setInRate(rate.getInRate());
            top.setOutRate(rate.getOutRate());
            top.setForwardName(forwardNames.getOrDefault(rate.getForwardId(), "转发#" + rate.getForwardId()));
            top.setPort(forwardPorts.get(rate.getForwardId()));
            topForwards.add(top);
        }
        return topForwards;
    }

    /**
     * 最高用户：按用户聚合转发速率
     */
    private FlowRateTopDto buildTopUser(List<RealtimeFlowTracker.ForwardRate> rates) {
        Map<Integer, Long> userRates = new HashMap<>();
        Map<Integer, String> userNames = new HashMap<>();
        for (RealtimeFlowTracker.ForwardRate rate : rates) {
            if (rate.getUserId() == null) {
                continue;
            }
            userRates.merge(rate.getUserId(), rate.getTotalRate(), Long::sum);
            userNames.putIfAbsent(rate.getUserId(), rate.getUserName());
        }
        Map.Entry<Integer, Long> top = userRates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (top == null) {
            return null;
        }
        FlowRateTopDto dto = new FlowRateTopDto();
        dto.setUserId(top.getKey());
        dto.setUserName(userNames.get(top.getKey()));
        dto.setTotalRate(top.getValue());
        return dto;
    }

    private double percent(long value, long total) {
        if (total <= 0) {
            return 0D;
        }
        return Math.round(value * 10000.0 / total) / 100.0;
    }

}

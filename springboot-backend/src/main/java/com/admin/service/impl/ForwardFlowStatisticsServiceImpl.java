package com.admin.service.impl;

import com.admin.common.dto.ForwardFlowAggregateDto;
import com.admin.common.dto.ForwardFlowRankItemDto;
import com.admin.common.dto.ForwardFlowRankingDto;
import com.admin.common.utils.FlowCounterDeltaCalculator;
import com.admin.common.utils.FlowStatisticsRange;
import com.admin.entity.Forward;
import com.admin.entity.ForwardFlowStatistics;
import com.admin.entity.ForwardPort;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardFlowStatisticsMapper;
import com.admin.service.ForwardFlowStatisticsService;
import com.admin.service.ForwardPortService;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发流量统计服务实现类
 * </p>
 *
 * @since 2.0.7-beta
 */
@Slf4j
@Service
public class ForwardFlowStatisticsServiceImpl
        extends ServiceImpl<ForwardFlowStatisticsMapper, ForwardFlowStatistics>
        implements ForwardFlowStatisticsService {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 流量方向口径：入站 + 出站
     */
    private static final String TRAFFIC_SCOPE = "in+out";

    private static final String TRAFFIC_SCOPE_LABEL = "入站 + 出站";

    private final ForwardFlowStatisticsMapper forwardFlowStatisticsMapper;

    private final ForwardService forwardService;

    private final ForwardPortService forwardPortService;

    private final TunnelService tunnelService;

    /**
     * 内存中的最近采集时间：采样点只在有流量时落库，这里额外记录任务运行时间，便于界面展示采集是否正常
     */
    private volatile Long lastCollectTime;

    public ForwardFlowStatisticsServiceImpl(ForwardFlowStatisticsMapper forwardFlowStatisticsMapper,
                                            ForwardService forwardService,
                                            ForwardPortService forwardPortService,
                                            TunnelService tunnelService) {
        this.forwardFlowStatisticsMapper = forwardFlowStatisticsMapper;
        this.forwardService = forwardService;
        this.forwardPortService = forwardPortService;
        this.tunnelService = tunnelService;
    }

    @Override
    public synchronized int collectStatistics() {
        long now = System.currentTimeMillis();
        List<Forward> forwards = forwardService.list();
        this.lastCollectTime = now;

        if (forwards == null || forwards.isEmpty()) {
            log.info("端口流量采集完成：暂无端口转发");
            return 0;
        }

        List<Long> forwardIds = forwards.stream()
                .map(Forward::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, List<ForwardPort>> portMap = loadForwardPortMap(forwardIds);
        Map<Long, ForwardFlowStatistics> latestMap = loadLatestStatistics(forwardIds);

        String sampleTime = TIME_FORMATTER.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));
        List<ForwardFlowStatistics> records = new ArrayList<>();
        int skipped = 0;

        for (Forward forward : forwards) {
            if (forward.getId() == null) {
                continue;
            }

            ForwardFlowStatistics previous = latestMap.get(forward.getId());
            FlowCounterDeltaCalculator.FlowCounterDelta delta = FlowCounterDeltaCalculator.calculate(
                    previous == null ? null : previous.getInFlow(),
                    previous == null ? null : previous.getOutFlow(),
                    forward.getInFlow(),
                    forward.getOutFlow()
            );

            // 无历史记录时必须落库建立基线；有历史记录时只在产生新增流量时落库，
            // 避免无流量端口每 5 分钟写一条记录，同时保证下一次差额仍以最近落库值为基线。
            if (previous != null && delta.getTotalDelta() == 0L) {
                skipped++;
                continue;
            }

            records.add(buildRecord(forward, portMap.get(forward.getId()), delta, now, sampleTime));

            if (delta.isReset()) {
                log.warn("端口转发 {} 流量计数器发生回退，按重置处理：入站 {} -> {}，出站 {} -> {}",
                        forward.getId(),
                        previous.getInFlow(), forward.getInFlow(),
                        previous.getOutFlow(), forward.getOutFlow());
            }
        }

        int saved = 0;
        for (ForwardFlowStatistics record : records) {
            saved += forwardFlowStatisticsMapper.insert(record);
        }

        log.info("端口流量采集完成：转发 {} 条，写入 {} 条，跳过 {} 条（无新增流量）",
                forwards.size(), saved, skipped);
        return saved;
    }

    @Override
    public ForwardFlowRankingDto getRanking(FlowStatisticsRange range) {
        return getRanking(range, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    @Override
    public ForwardFlowRankingDto getRanking(FlowStatisticsRange range, long nowMillis, ZoneId zone) {
        FlowStatisticsRange effectiveRange = range == null ? FlowStatisticsRange.TODAY : range;
        ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;

        long startTime = effectiveRange.resolveStartTime(nowMillis, effectiveZone);
        long endTime = nowMillis;

        List<ForwardFlowAggregateDto> aggregates = forwardFlowStatisticsMapper
                .selectFlowSumsByRange(startTime, endTime);

        ForwardFlowRankingDto ranking = new ForwardFlowRankingDto();
        ranking.setRange(effectiveRange.getCode());
        ranking.setRangeLabel(effectiveRange.getLabel());
        ranking.setDays(effectiveRange.getDays());
        ranking.setStartTime(startTime);
        ranking.setEndTime(endTime);
        ranking.setStartTimeText(TIME_FORMATTER.format(Instant.ofEpochMilli(startTime).atZone(effectiveZone)));
        ranking.setEndTimeText(TIME_FORMATTER.format(Instant.ofEpochMilli(endTime).atZone(effectiveZone)));
        ranking.setSampleIntervalMinutes(SAMPLE_INTERVAL_MINUTES);
        ranking.setRetentionDays(RETENTION_DAYS);
        ranking.setTrafficScope(TRAFFIC_SCOPE);
        ranking.setTrafficScopeLabel(TRAFFIC_SCOPE_LABEL);
        ranking.setGeneratedTime(nowMillis);

        List<ForwardFlowRankItemDto> items = buildRankItems(aggregates);
        long totalInFlow = 0L;
        long totalOutFlow = 0L;
        int portCount = 0;
        for (ForwardFlowRankItemDto item : items) {
            totalInFlow += safe(item.getInFlow());
            totalOutFlow += safe(item.getOutFlow());
            // 每条转发至少对应一个入口端口
            portCount += item.getPorts() == null || item.getPorts().isEmpty() ? 1 : item.getPorts().size();
        }

        ranking.setItems(items);
        ranking.setForwardCount(items.size());
        ranking.setPortCount(portCount);
        ranking.setTotalInFlow(totalInFlow);
        ranking.setTotalOutFlow(totalOutFlow);
        ranking.setTotalFlow(totalInFlow + totalOutFlow);

        Long lastCollect = getLastCollectTime();
        ranking.setLastCollectTime(lastCollect);
        ranking.setLastCollectTimeText(lastCollect == null ? null
                : TIME_FORMATTER.format(Instant.ofEpochMilli(lastCollect).atZone(effectiveZone)));

        return ranking;
    }

    @Override
    public int cleanupExpiredStatistics(int retentionDays) {
        int days = retentionDays <= 0 ? RETENTION_DAYS : retentionDays;
        long cutoff = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000;
        int deleted = forwardFlowStatisticsMapper.deleteBefore(cutoff);
        if (deleted > 0) {
            log.info("清理端口流量采集记录 {} 条（保留 {} 天）", deleted, days);
        }
        return deleted;
    }

    @Override
    public Long getLastCollectTime() {
        Long inMemory = this.lastCollectTime;
        Long stored = forwardFlowStatisticsMapper.selectLastCollectTime();
        if (inMemory == null) {
            return stored;
        }
        if (stored == null) {
            return inMemory;
        }
        return Math.max(inMemory, stored);
    }

    /**
     * 组装排行条目：补齐转发名称、端口、隧道等展示信息，并按总流量降序排名
     */
    private List<ForwardFlowRankItemDto> buildRankItems(List<ForwardFlowAggregateDto> aggregates) {
        if (aggregates == null || aggregates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> forwardIds = aggregates.stream()
                .map(ForwardFlowAggregateDto::getForwardId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (forwardIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Forward> currentForwards = new HashMap<>();
        List<Forward> forwardList = forwardService.listByIds(forwardIds);
        if (forwardList != null) {
            for (Forward forward : forwardList) {
                currentForwards.put(forward.getId(), forward);
            }
        }

        Map<Long, List<ForwardPort>> currentPorts = loadForwardPortMap(forwardIds);
        Map<Long, ForwardFlowStatistics> latestMap = loadLatestStatistics(forwardIds);

        Map<Long, ForwardDisplayInfo> displayInfos = new HashMap<>();
        Set<Integer> tunnelIds = new LinkedHashSet<>();
        for (Long forwardId : forwardIds) {
            ForwardDisplayInfo info = resolveForwardInfo(forwardId, currentForwards, latestMap);
            displayInfos.put(forwardId, info);
            if (info.tunnelId != null) {
                tunnelIds.add(info.tunnelId);
            }
        }
        Map<Integer, String> tunnelNames = loadTunnelNames(tunnelIds);

        List<ForwardFlowRankItemDto> items = new ArrayList<>(aggregates.size());
        for (ForwardFlowAggregateDto aggregate : aggregates) {
            if (aggregate.getForwardId() == null) {
                continue;
            }
            // 基线记录（首次采集）没有新增流量，不计入排行
            if (aggregate.getTotalFlow() == null || aggregate.getTotalFlow() <= 0) {
                continue;
            }
            ForwardDisplayInfo info = displayInfos.get(aggregate.getForwardId());
            ForwardFlowRankItemDto item = new ForwardFlowRankItemDto();
            item.setForwardId(aggregate.getForwardId());
            item.setForwardName(info.forwardName);
            item.setDeleted(info.deleted);
            item.setStatus(info.status);
            item.setTunnelId(info.tunnelId);
            item.setTunnelName(info.tunnelId == null ? "" : tunnelNames.getOrDefault(info.tunnelId, ""));
            item.setUserId(info.userId);
            item.setUserName(info.userName);

            List<ForwardPort> ports = currentPorts.get(aggregate.getForwardId());
            if (ports != null && !ports.isEmpty()) {
                List<Integer> portList = ports.stream()
                        .map(ForwardPort::getPort)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                item.setPorts(portList);
                item.setPort(portList.isEmpty() ? info.port : portList.get(0));
                item.setNodeId(ports.get(0).getNodeId());
            } else {
                // 转发已删除：使用最后一次采集时的端口快照
                List<Integer> snapshotPorts = parsePorts(info.ports, info.port);
                item.setPorts(snapshotPorts);
                item.setPort(snapshotPorts.isEmpty() ? info.port : snapshotPorts.get(0));
                item.setNodeId(info.nodeId);
            }

            item.setInFlow(safe(aggregate.getInFlow()));
            item.setOutFlow(safe(aggregate.getOutFlow()));
            item.setTotalFlow(safe(aggregate.getTotalFlow()));
            item.setSampleCount(aggregate.getSampleCount() == null ? 0 : aggregate.getSampleCount());
            item.setFirstTime(aggregate.getFirstTime());
            item.setLastTime(aggregate.getLastTime());
            items.add(item);
        }

        // 按区间内总流量降序，流量相同时按转发ID升序，保证结果稳定
        items.sort(Comparator.comparing(ForwardFlowRankItemDto::getTotalFlow).reversed()
                .thenComparing(ForwardFlowRankItemDto::getForwardId));
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setRank(i + 1);
        }
        return items;
    }

    /**
     * 转发展示信息：优先使用当前转发数据，转发被删除时回退到最后一次采集的快照
     */
    private ForwardDisplayInfo resolveForwardInfo(Long forwardId,
                                                  Map<Long, Forward> currentForwards,
                                                  Map<Long, ForwardFlowStatistics> latestMap) {
        ForwardDisplayInfo info = new ForwardDisplayInfo();
        Forward forward = currentForwards.get(forwardId);
        ForwardFlowStatistics snapshot = latestMap.get(forwardId);

        if (forward != null) {
            info.forwardName = forward.getName();
            info.tunnelId = forward.getTunnelId();
            info.userId = forward.getUserId();
            info.userName = forward.getUserName();
            info.status = forward.getStatus();
            info.deleted = false;
        } else {
            info.forwardName = snapshot == null || snapshot.getForwardName() == null
                    ? "转发#" + forwardId : snapshot.getForwardName();
            info.tunnelId = snapshot == null ? null : snapshot.getTunnelId();
            info.userId = snapshot == null ? null : snapshot.getUserId();
            info.userName = snapshot == null ? "" : snapshot.getUserName();
            info.status = null;
            info.deleted = true;
        }

        if (snapshot != null) {
            info.port = snapshot.getPort();
            info.ports = snapshot.getPorts();
            info.nodeId = snapshot.getNodeId();
        }
        return info;
    }

    /**
     * 解析采集快照中的端口列表（英文逗号分隔），快照缺失时回退到主端口
     */
    private List<Integer> parsePorts(String ports, Integer fallbackPort) {
        List<Integer> result = new ArrayList<>();
        if (ports != null && !ports.trim().isEmpty()) {
            for (String part : ports.split(",")) {
                try {
                    int port = Integer.parseInt(part.trim());
                    if (port > 0 && !result.contains(port)) {
                        result.add(port);
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略脏数据，保证接口可用
                }
            }
        }
        if (result.isEmpty() && fallbackPort != null) {
            result.add(fallbackPort);
        }
        return result;
    }

    private ForwardFlowStatistics buildRecord(Forward forward, List<ForwardPort> ports,
                                              FlowCounterDeltaCalculator.FlowCounterDelta delta,
                                              long now, String sampleTime) {
        ForwardFlowStatistics record = new ForwardFlowStatistics();
        record.setForwardId(forward.getId());
        record.setForwardName(forward.getName());
        record.setTunnelId(forward.getTunnelId());
        record.setUserId(forward.getUserId());
        record.setUserName(forward.getUserName());
        record.setInFlow(safe(forward.getInFlow()));
        record.setOutFlow(safe(forward.getOutFlow()));
        record.setInFlowDelta(delta.getInDelta());
        record.setOutFlowDelta(delta.getOutDelta());
        record.setFlow(delta.getTotalDelta());
        record.setTotalFlow(safe(forward.getInFlow()) + safe(forward.getOutFlow()));
        record.setResetFlag(delta.isReset() ? 1 : 0);
        record.setCreatedTime(now);
        record.setTime(sampleTime);

        if (ports != null && !ports.isEmpty()) {
            List<Integer> portList = ports.stream()
                    .map(ForwardPort::getPort)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!portList.isEmpty()) {
                record.setPort(portList.get(0));
                record.setPorts(portList.stream().map(String::valueOf).collect(Collectors.joining(",")));
            }
            record.setNodeId(ports.get(0).getNodeId());
        }
        return record;
    }

    private Map<Long, List<ForwardPort>> loadForwardPortMap(List<Long> forwardIds) {
        if (forwardIds == null || forwardIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ForwardPort> ports = forwardPortService.list(
                new QueryWrapper<ForwardPort>()
                        .in("forward_id", forwardIds)
                        .orderByAsc("id")
        );
        Map<Long, List<ForwardPort>> portMap = new HashMap<>();
        for (ForwardPort port : ports) {
            portMap.computeIfAbsent(port.getForwardId(), key -> new ArrayList<>()).add(port);
        }
        return portMap;
    }

    private Map<Long, ForwardFlowStatistics> loadLatestStatistics(List<Long> forwardIds) {
        if (forwardIds == null || forwardIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ForwardFlowStatistics> latest = forwardFlowStatisticsMapper.selectLatestByForwardIds(forwardIds);
        Map<Long, ForwardFlowStatistics> latestMap = new HashMap<>();
        if (latest != null) {
            for (ForwardFlowStatistics item : latest) {
                latestMap.put(item.getForwardId(), item);
            }
        }
        return latestMap;
    }

    private Map<Integer, String> loadTunnelNames(Set<Integer> tunnelIds) {
        if (tunnelIds == null || tunnelIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Tunnel> tunnels = tunnelService.listByIds(tunnelIds);
        Map<Integer, String> names = new HashMap<>();
        if (tunnels != null) {
            for (Tunnel tunnel : tunnels) {
                names.put(tunnel.getId().intValue(), tunnel.getName());
            }
        }
        return names;
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    /**
     * 转发展示信息（当前数据或历史快照）
     */
    private static final class ForwardDisplayInfo {
        private String forwardName;
        private Integer port;
        private String ports;
        private Long nodeId;
        private Integer tunnelId;
        private Integer userId;
        private String userName;
        private Integer status;
        private boolean deleted;
    }

}

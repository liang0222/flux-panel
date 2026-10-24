package com.admin.service;

import com.admin.common.dto.ForwardFlowAggregateDto;
import com.admin.common.dto.ForwardFlowRankItemDto;
import com.admin.common.dto.ForwardFlowRankingDto;
import com.admin.common.utils.FlowStatisticsRange;
import com.admin.entity.Forward;
import com.admin.entity.ForwardFlowStatistics;
import com.admin.entity.ForwardPort;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardFlowStatisticsMapper;
import com.admin.service.impl.ForwardFlowStatisticsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端口转发流量采集与排行榜聚合测试
 * 通过 Mock 依赖覆盖采集写入、计数器重置、无流量跳过、区间聚合、排序与历史快照场景。
 */
class ForwardFlowStatisticsServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private ForwardFlowStatisticsMapper mapper;

    private ForwardService forwardService;

    private ForwardPortService forwardPortService;

    private TunnelService tunnelService;

    private ForwardFlowStatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ForwardFlowStatisticsMapper.class);
        forwardService = mock(ForwardService.class);
        forwardPortService = mock(ForwardPortService.class);
        tunnelService = mock(TunnelService.class);
        service = new ForwardFlowStatisticsServiceImpl(mapper, forwardService, forwardPortService, tunnelService);

        when(forwardService.list()).thenReturn(Collections.emptyList());
        when(forwardPortService.list(any())).thenReturn(Collections.emptyList());
        when(forwardService.listByIds(any())).thenReturn(Collections.emptyList());
        when(tunnelService.listByIds(any())).thenReturn(Collections.emptyList());
        when(mapper.selectLatestByForwardIds(any())).thenReturn(Collections.emptyList());
        when(mapper.selectFlowSumsByRange(anyLong(), anyLong())).thenReturn(Collections.emptyList());
        when(mapper.selectLastCollectTime()).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
    }

    @Test
    @DisplayName("首次采集写入基线记录，不计入历史累计流量")
    void collectFirstSampleWritesBaseline() {
        when(forwardService.list()).thenReturn(List.of(forward(1L, 100L, 50L)));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 10L, 23333)));

        int saved = service.collectStatistics();

        assertEquals(1, saved);
        ForwardFlowStatistics record = captureInserted();
        assertEquals(1L, record.getForwardId());
        assertEquals("转发1", record.getForwardName());
        assertEquals(23333, record.getPort());
        assertEquals("23333", record.getPorts());
        assertEquals(10L, record.getNodeId());
        assertEquals(5, record.getTunnelId());
        assertEquals(2, record.getUserId());
        assertEquals(100L, record.getInFlow());
        assertEquals(50L, record.getOutFlow());
        assertEquals(150L, record.getTotalFlow());
        assertEquals(0L, record.getInFlowDelta());
        assertEquals(0L, record.getOutFlowDelta());
        assertEquals(0L, record.getFlow());
        assertEquals(0, record.getResetFlag());
        assertNotNull(record.getCreatedTime());
        assertNotNull(record.getTime());
    }

    @Test
    @DisplayName("相邻采集按差额写入新增流量")
    void collectSecondSampleWritesDelta() {
        when(forwardService.list()).thenReturn(List.of(forward(1L, 180L, 70L)));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 10L, 23333)));
        when(mapper.selectLatestByForwardIds(any())).thenReturn(List.of(sample(1L, 100L, 50L)));

        int saved = service.collectStatistics();

        assertEquals(1, saved);
        ForwardFlowStatistics record = captureInserted();
        assertEquals(80L, record.getInFlowDelta());
        assertEquals(20L, record.getOutFlowDelta());
        assertEquals(100L, record.getFlow());
        assertEquals(180L, record.getInFlow());
        assertEquals(70L, record.getOutFlow());
        assertEquals(250L, record.getTotalFlow());
        assertEquals(0, record.getResetFlag());
    }

    @Test
    @DisplayName("计数器回退时按重置处理，写入非负增量")
    void collectCounterResetWritesCurrentValue() {
        when(forwardService.list()).thenReturn(List.of(forward(1L, 30L, 20L)));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 10L, 23333)));
        when(mapper.selectLatestByForwardIds(any())).thenReturn(List.of(sample(1L, 1000L, 500L)));

        service.collectStatistics();

        ForwardFlowStatistics record = captureInserted();
        assertEquals(30L, record.getInFlowDelta());
        assertEquals(20L, record.getOutFlowDelta());
        assertEquals(50L, record.getFlow());
        assertEquals(1, record.getResetFlag());
        assertTrue(record.getFlow() >= 0);
    }

    @Test
    @DisplayName("采集缺失后恢复时差额覆盖缺失区间，不会重复累计")
    void collectAfterMissingSamples() {
        ForwardFlowStatistics old = sample(1L, 100L, 0L);
        old.setCreatedTime(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000);
        when(mapper.selectLatestByForwardIds(any())).thenReturn(List.of(old));
        when(forwardService.list()).thenReturn(List.of(forward(1L, 1000L, 0L)));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 10L, 23333)));

        service.collectStatistics();

        ForwardFlowStatistics record = captureInserted();
        assertEquals(900L, record.getInFlowDelta());
        assertEquals(0, record.getResetFlag());
    }

    @Test
    @DisplayName("无新增流量的端口跳过写入，避免无意义记录")
    void collectSkipsUnchangedForward() {
        when(forwardService.list()).thenReturn(List.of(forward(1L, 100L, 50L), forward(2L, 100L, 50L)));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 10L, 23333), port(2L, 10L, 23334)));
        when(mapper.selectLatestByForwardIds(any())).thenReturn(List.of(
                sample(1L, 100L, 50L),
                sample(2L, 90L, 40L)
        ));

        int saved = service.collectStatistics();

        assertEquals(1, saved);
        ForwardFlowStatistics record = captureInserted();
        assertEquals(2L, record.getForwardId());
        assertEquals(10L, record.getInFlowDelta());
        assertEquals(10L, record.getOutFlowDelta());
    }

    @Test
    @DisplayName("转发没有入口端口记录时仍按转发维度采集")
    void collectWithoutForwardPort() {
        when(forwardService.list()).thenReturn(List.of(forward(1L, 10L, 20L)));

        int saved = service.collectStatistics();

        assertEquals(1, saved);
        ForwardFlowStatistics record = captureInserted();
        assertNull(record.getPort());
        assertNull(record.getNodeId());
        assertEquals(30L, record.getTotalFlow());
    }

    @Test
    @DisplayName("没有任何端口转发时采集不写入记录")
    void collectWithoutForwards() {
        int saved = service.collectStatistics();

        assertEquals(0, saved);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("排行榜按总流量降序排列，流量相同按转发ID升序")
    void rankingSortsByTotalFlow() {
        long now = fixedNow();
        List<ForwardFlowAggregateDto> aggregates = List.of(
                aggregate(3L, 10L, 20L),
                aggregate(2L, 300L, 200L),
                aggregate(1L, 250L, 250L)
        );
        when(mapper.selectFlowSumsByRange(anyLong(), anyLong())).thenReturn(aggregates);
        when(forwardService.listByIds(any())).thenReturn(List.of(
                forward(1L, 250L, 250L), forward(2L, 300L, 200L), forward(3L, 10L, 20L)));
        when(forwardPortService.list(any())).thenReturn(List.of(
                port(1L, 10L, 30001), port(2L, 10L, 30002), port(3L, 10L, 30003)));
        when(tunnelService.listByIds(any())).thenReturn(List.of(tunnel(5, "香港")));
        when(mapper.selectLastCollectTime()).thenReturn(now - 60_000L);

        ForwardFlowRankingDto ranking = service.getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);

        assertEquals(3, ranking.getItems().size());
        assertEquals(1L, ranking.getItems().get(0).getForwardId());
        assertEquals(1, ranking.getItems().get(0).getRank());
        assertEquals(500L, ranking.getItems().get(0).getTotalFlow());
        assertEquals(2L, ranking.getItems().get(1).getForwardId());
        assertEquals(2, ranking.getItems().get(1).getRank());
        assertEquals(3L, ranking.getItems().get(2).getForwardId());
        assertEquals(3, ranking.getItems().get(2).getRank());

        assertEquals(30001, ranking.getItems().get(0).getPort());
        assertEquals(List.of(30001), ranking.getItems().get(0).getPorts());
        assertEquals("香港", ranking.getItems().get(0).getTunnelName());

        assertEquals(1030L, ranking.getTotalFlow());
        assertEquals(560L, ranking.getTotalInFlow());
        assertEquals(470L, ranking.getTotalOutFlow());
        assertEquals(3, ranking.getForwardCount());
        assertEquals(3, ranking.getPortCount());
        assertEquals("today", ranking.getRange());
        assertEquals("今天", ranking.getRangeLabel());
        assertEquals(5, ranking.getSampleIntervalMinutes());
        assertEquals(31, ranking.getRetentionDays());
        assertEquals("入站 + 出站", ranking.getTrafficScopeLabel());
        assertNotNull(ranking.getLastCollectTimeText());
    }

    @Test
    @DisplayName("已删除的转发使用最后一次采集快照展示历史流量")
    void rankingUsesSnapshotForDeletedForward() {
        long now = fixedNow();
        when(mapper.selectFlowSumsByRange(anyLong(), anyLong())).thenReturn(List.of(aggregate(99L, 100L, 200L)));
        when(mapper.selectLatestByForwardIds(any())).thenReturn(List.of(snapshot(99L, "老转发", 8080, 5)));
        when(tunnelService.listByIds(any())).thenReturn(List.of(tunnel(5, "日本")));

        ForwardFlowRankingDto ranking = service.getRanking(FlowStatisticsRange.LAST_7_DAYS, now, SHANGHAI);

        assertEquals(1, ranking.getItems().size());
        ForwardFlowRankItemDto item = ranking.getItems().get(0);
        assertTrue(item.getDeleted());
        assertNull(item.getStatus());
        assertEquals("老转发", item.getForwardName());
        assertEquals(8080, item.getPort());
        assertEquals(List.of(8080), item.getPorts());
        assertEquals("日本", item.getTunnelName());
        assertEquals(300L, item.getTotalFlow());
        assertEquals("7d", ranking.getRange());
        assertEquals(7, ranking.getDays());
    }

    @Test
    @DisplayName("区间内只有基线记录（无新增流量）的端口不进入排行")
    void rankingSkipsBaselineOnlyForward() {
        long now = fixedNow();
        when(mapper.selectFlowSumsByRange(anyLong(), anyLong())).thenReturn(List.of(
                aggregate(1L, 0L, 0L),
                aggregate(2L, 10L, 5L)
        ));
        when(forwardService.listByIds(any())).thenReturn(List.of(
                forward(1L, 0L, 0L), forward(2L, 10L, 5L)));
        when(forwardPortService.list(any())).thenReturn(List.of(
                port(1L, 10L, 30001), port(2L, 10L, 30002)));

        ForwardFlowRankingDto ranking = service.getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);

        assertEquals(1, ranking.getItems().size());
        assertEquals(2L, ranking.getItems().get(0).getForwardId());
        assertEquals(1, ranking.getForwardCount());
        assertEquals(1, ranking.getPortCount());
        assertEquals(15L, ranking.getTotalFlow());
    }

    @Test
    @DisplayName("一条转发对应多个入口端口时展示全部端口并计入口端口数")
    void rankingCountsAllEntryPorts() {
        long now = fixedNow();
        when(mapper.selectFlowSumsByRange(anyLong(), anyLong())).thenReturn(List.of(aggregate(1L, 10L, 10L)));
        when(forwardService.listByIds(any())).thenReturn(List.of(forward(1L, 10L, 10L)));
        when(forwardPortService.list(any())).thenReturn(List.of(
                port(1L, 10L, 30001), port(1L, 11L, 30002)));

        ForwardFlowRankingDto ranking = service.getRanking(FlowStatisticsRange.LAST_3_DAYS, now, SHANGHAI);

        assertEquals(1, ranking.getItems().size());
        ForwardFlowRankItemDto item = ranking.getItems().get(0);
        assertEquals(30001, item.getPort());
        assertEquals(List.of(30001, 30002), item.getPorts());
        assertEquals(10L, item.getNodeId());
        assertEquals(2, ranking.getPortCount());
        assertEquals(1, ranking.getForwardCount());
    }

    @Test
    @DisplayName("区间内无采集数据时返回空排行榜")
    void rankingWithoutData() {
        long now = fixedNow();

        ForwardFlowRankingDto ranking = service.getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);

        assertTrue(ranking.getItems().isEmpty());
        assertEquals(0L, ranking.getTotalFlow());
        assertEquals(0, ranking.getForwardCount());
        assertEquals(0, ranking.getPortCount());
        assertNull(ranking.getLastCollectTime());
        assertNull(ranking.getLastCollectTimeText());
        assertEquals(now, ranking.getEndTime());
    }

    @Test
    @DisplayName("统计范围按项目时区从当日零点起算")
    void rankingUsesTimezoneAwareRangeBoundaries() {
        long now = fixedNow();

        service.getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);
        long todayStart = ZonedDateTime.of(2026, 10, 20, 0, 0, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();
        verify(mapper).selectFlowSumsByRange(eq(todayStart), eq(now));

        service.getRanking(FlowStatisticsRange.LAST_3_DAYS, now, SHANGHAI);
        long threeDayStart = ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();
        verify(mapper).selectFlowSumsByRange(eq(threeDayStart), eq(now));

        service.getRanking(FlowStatisticsRange.LAST_30_DAYS, now, SHANGHAI);
        long thirtyDayStart = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();
        verify(mapper).selectFlowSumsByRange(eq(thirtyDayStart), eq(now));
    }

    @Test
    @DisplayName("清理任务只删除保留期之前的数据")
    void cleanupDeletesExpiredRecords() {
        when(mapper.deleteBefore(anyLong())).thenReturn(7);

        long before = System.currentTimeMillis();
        int deleted = service.cleanupExpiredStatistics(31);
        long after = System.currentTimeMillis();

        assertEquals(7, deleted);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(mapper).deleteBefore(captor.capture());
        long cutoff = captor.getValue();
        long dayMillis = 24L * 60 * 60 * 1000;

        assertTrue(cutoff >= before - 31 * dayMillis - 2000);
        assertTrue(cutoff <= after - 31 * dayMillis + 2000);
    }

    @Test
    @DisplayName("清理任务未指定保留天数时使用默认 31 天")
    void cleanupUsesDefaultRetention() {
        when(mapper.deleteBefore(anyLong())).thenReturn(0);

        service.cleanupExpiredStatistics(0);

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(mapper).deleteBefore(captor.capture());
        long expected = System.currentTimeMillis() - (long) ForwardFlowStatisticsService.RETENTION_DAYS * 24 * 60 * 60 * 1000;
        assertTrue(Math.abs(captor.getValue() - expected) < 5000);
    }

    @Test
    @DisplayName("最近采集时间取内存运行时间与落库时间的较大值")
    void lastCollectTimeMergesMemoryAndStorage() {
        long now = System.currentTimeMillis();
        when(forwardService.list()).thenReturn(Collections.emptyList());
        service.collectStatistics();

        when(mapper.selectLastCollectTime()).thenReturn(now - 10 * 60 * 1000L);
        Long last = service.getLastCollectTime();
        assertNotNull(last);
        assertTrue(last >= now - 1000);

        when(mapper.selectLastCollectTime()).thenReturn(now + 60 * 1000L);
        assertEquals(now + 60 * 1000L, service.getLastCollectTime());
    }

    // ========== 测试辅助方法 ==========

    private long fixedNow() {
        return ZonedDateTime.of(2026, 10, 20, 11, 37, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();
    }

    private Forward forward(long id, Long inFlow, Long outFlow) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setName("转发" + id);
        forward.setTunnelId(5);
        forward.setUserId(2);
        forward.setUserName("user" + id);
        forward.setInFlow(inFlow);
        forward.setOutFlow(outFlow);
        forward.setStatus(1);
        return forward;
    }

    private ForwardPort port(long forwardId, long nodeId, int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(forwardId);
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(port);
        return forwardPort;
    }

    private ForwardFlowStatistics sample(long forwardId, Long inFlow, Long outFlow) {
        ForwardFlowStatistics sample = new ForwardFlowStatistics();
        sample.setForwardId(forwardId);
        sample.setInFlow(inFlow);
        sample.setOutFlow(outFlow);
        sample.setTotalFlow(inFlow + outFlow);
        sample.setCreatedTime(System.currentTimeMillis() - 5 * 60 * 1000L);
        return sample;
    }

    private ForwardFlowStatistics snapshot(long forwardId, String name, Integer port, Integer tunnelId) {
        ForwardFlowStatistics sample = new ForwardFlowStatistics();
        sample.setForwardId(forwardId);
        sample.setForwardName(name);
        sample.setPort(port);
        sample.setNodeId(10L);
        sample.setTunnelId(tunnelId);
        sample.setUserId(2);
        sample.setUserName("user");
        sample.setInFlow(100L);
        sample.setOutFlow(200L);
        sample.setCreatedTime(System.currentTimeMillis() - 60_000L);
        return sample;
    }

    private ForwardFlowAggregateDto aggregate(long forwardId, long inFlow, long outFlow) {
        ForwardFlowAggregateDto aggregate = new ForwardFlowAggregateDto();
        aggregate.setForwardId(forwardId);
        aggregate.setInFlow(inFlow);
        aggregate.setOutFlow(outFlow);
        aggregate.setTotalFlow(inFlow + outFlow);
        aggregate.setSampleCount(12);
        aggregate.setFirstTime(System.currentTimeMillis() - 3600_000L);
        aggregate.setLastTime(System.currentTimeMillis() - 300_000L);
        return aggregate;
    }

    private Tunnel tunnel(int id, String name) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId((long) id);
        tunnel.setName(name);
        return tunnel;
    }

    private ForwardFlowStatistics captureInserted() {
        ArgumentCaptor<ForwardFlowStatistics> captor = ArgumentCaptor.forClass(ForwardFlowStatistics.class);
        verify(mapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

}

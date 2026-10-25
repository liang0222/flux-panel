package com.admin.service;

import com.admin.common.dto.FlowRateDto;
import com.admin.common.dto.FlowRateTopDto;
import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时速率汇总测试：总量、占比、Top 端口/用户、管理员与普通用户范围。
 */
class FlowRateServiceTest {

    private RealtimeFlowTracker tracker;

    private ForwardService forwardService;

    private ForwardPortService forwardPortService;

    private FlowRateService flowRateService;

    @BeforeEach
    void setUp() {
        tracker = mock(RealtimeFlowTracker.class);
        forwardService = mock(ForwardService.class);
        forwardPortService = mock(ForwardPortService.class);
        flowRateService = new FlowRateService(tracker, forwardService, forwardPortService);
        when(tracker.currentRates(any())).thenReturn(Collections.emptyList());
        when(tracker.lastReportTime()).thenReturn(1_700_000_000_000L);
        when(forwardService.listByIds(anyList())).thenReturn(Collections.emptyList());
        when(forwardPortService.list(any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("管理员范围汇总全部转发并给出最忙端口与最高用户")
    void adminAggregatesAllForwards() {
        when(tracker.currentRates(null)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(1L, 10, "user1", 100L, 20L),
                new RealtimeFlowTracker.ForwardRate(2L, 20, "user2", 50L, 10L)
        ));
        when(forwardService.listByIds(anyList())).thenReturn(List.of(forward(1L, "香港-01"), forward(2L, "日本-02")));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 23333), port(2L, 24444)));

        FlowRateDto dto = flowRateService.getCurrentRate(1, true);

        assertEquals("all", dto.getScope());
        assertEquals(150L, dto.getInRate());
        assertEquals(30L, dto.getOutRate());
        assertEquals(180L, dto.getTotalRate());
        assertEquals(83.33, dto.getInPercent(), 0.001);
        assertEquals(16.67, dto.getOutPercent(), 0.001);
        assertEquals(2, dto.getActiveForwardCount());
        assertEquals(2, dto.getActiveUserCount());
        assertEquals(10, dto.getWindowSeconds());
        assertEquals(1_700_000_000_000L, dto.getSampleTime().longValue());

        assertEquals("香港-01", dto.getTopForward().getForwardName());
        assertEquals(23333, dto.getTopForward().getPort());
        assertEquals(120L, dto.getTopForward().getTotalRate());

        assertEquals(2, dto.getTopForwards().size());
        assertEquals(dto.getTopForward().getForwardId(), dto.getTopForwards().get(0).getForwardId());
        assertEquals(23333, dto.getTopForwards().get(0).getPort());
        assertEquals("日本-02", dto.getTopForwards().get(1).getForwardName());
        assertEquals(24444, dto.getTopForwards().get(1).getPort());

        assertEquals(10, dto.getTopUser().getUserId());
        assertEquals("user1", dto.getTopUser().getUserName());
        assertEquals(120L, dto.getTopUser().getTotalRate());
    }

    @Test
    @DisplayName("普通用户只统计自己的转发，不返回最高用户")
    void normalUserScopedToSelf() {
        when(tracker.currentRates(10)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(1L, 10, "user1", 100L, 20L)));
        when(forwardService.listByIds(anyList())).thenReturn(List.of(forward(1L, "我的转发")));

        FlowRateDto dto = flowRateService.getCurrentRate(10, false);

        verify(tracker).currentRates(eq(10));
        assertEquals("self", dto.getScope());
        assertEquals(120L, dto.getTotalRate());
        assertEquals(1, dto.getActiveForwardCount());
        assertNull(dto.getTopUser());
        assertEquals("我的转发", dto.getTopForward().getForwardName());
        assertNull(dto.getTopForward().getPort());
        assertEquals(1, dto.getTopForwards().size());
    }

    @Test
    @DisplayName("没有流量时返回全 0，不产生 Top")
    void emptyRate() {
        FlowRateDto dto = flowRateService.getCurrentRate(10, false);

        assertEquals(0L, dto.getTotalRate());
        assertEquals(0L, dto.getInRate());
        assertEquals(0L, dto.getOutRate());
        assertEquals(0.0, dto.getInPercent(), 0.001);
        assertEquals(0.0, dto.getOutPercent(), 0.001);
        assertEquals(0, dto.getActiveForwardCount());
        assertEquals(0, dto.getActiveUserCount());
        assertNull(dto.getTopForward());
        assertNull(dto.getTopUser());
        assertTrue(dto.getTopForwards().isEmpty());
    }

    @Test
    @DisplayName("转发已删除时最忙端口回退为转发ID")
    void topForwardDeleted() {
        when(tracker.currentRates(null)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(99L, 10, "user1", 10L, 0L)));

        FlowRateDto dto = flowRateService.getCurrentRate(1, true);

        assertEquals("转发#99", dto.getTopForward().getForwardName());
        assertNull(dto.getTopForward().getPort());
    }

    @Test
    @DisplayName("端口排行只取前 10 条，保持总速率降序，且只查一次库")
    void topForwardsLimitedToTen() {
        List<RealtimeFlowTracker.ForwardRate> rates = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            // 速率随 i 递减，模拟 tracker 已按降序返回
            rates.add(new RealtimeFlowTracker.ForwardRate((long) i, 10, "user1", 1000L - i, 0L));
        }
        when(tracker.currentRates(null)).thenReturn(rates);
        List<Forward> forwards = new ArrayList<>();
        List<ForwardPort> ports = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            forwards.add(forward((long) i, "转发" + i));
            ports.add(port((long) i, 20000 + i));
        }
        when(forwardService.listByIds(anyList())).thenReturn(forwards);
        when(forwardPortService.list(any())).thenReturn(ports);

        FlowRateDto dto = flowRateService.getCurrentRate(1, true);

        assertEquals(10, dto.getTopForwards().size());
        assertEquals(15, dto.getActiveForwardCount());
        for (int i = 0; i < 10; i++) {
            FlowRateTopDto item = dto.getTopForwards().get(i);
            assertEquals((long) (i + 1), item.getForwardId());
            assertEquals(20000 + i + 1, item.getPort());
            assertEquals(1000L - (i + 1), item.getTotalRate());
        }
        // 第 11 名及以后不出现
        assertTrue(dto.getTopForwards().stream().noneMatch(item -> item.getForwardId() > 10));
        // 批量查询：转发与端口各一次，不随条数增长
        verify(forwardService, times(1)).listByIds(anyList());
        verify(forwardPortService, times(1)).list(any());
    }

    @Test
    @DisplayName("同一转发有多个端口时排行只展示第一个端口")
    void topForwardsUseFirstPort() {
        when(tracker.currentRates(null)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(1L, 10, "user1", 100L, 20L)));
        when(forwardService.listByIds(anyList())).thenReturn(List.of(forward(1L, "多端口转发")));
        when(forwardPortService.list(any())).thenReturn(List.of(port(1L, 30001), port(1L, 30002)));

        FlowRateDto dto = flowRateService.getCurrentRate(1, true);

        assertEquals(1, dto.getTopForwards().size());
        assertEquals(30001, dto.getTopForwards().get(0).getPort());
    }

    private Forward forward(Long id, String name) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setName(name);
        return forward;
    }

    private ForwardPort port(Long forwardId, int value) {
        ForwardPort port = new ForwardPort();
        port.setPort(value);
        port.setForwardId(forwardId);
        port.setNodeId(1L);
        return port;
    }

}

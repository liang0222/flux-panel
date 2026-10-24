package com.admin.service;

import com.admin.common.dto.FlowRateDto;
import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    }

    @Test
    @DisplayName("管理员范围汇总全部转发并给出最忙端口与最高用户")
    void adminAggregatesAllForwards() {
        when(tracker.currentRates(null)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(1L, 10, "user1", 100L, 20L),
                new RealtimeFlowTracker.ForwardRate(2L, 20, "user2", 50L, 10L)
        ));
        Forward forward = new Forward();
        forward.setId(1L);
        forward.setName("香港-01");
        when(forwardService.getById(1L)).thenReturn(forward);
        when(forwardPortService.list(any())).thenReturn(List.of(port(23333)));

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

        assertEquals(10, dto.getTopUser().getUserId());
        assertEquals("user1", dto.getTopUser().getUserName());
        assertEquals(120L, dto.getTopUser().getTotalRate());
    }

    @Test
    @DisplayName("普通用户只统计自己的转发，不返回最高用户")
    void normalUserScopedToSelf() {
        when(tracker.currentRates(10)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(1L, 10, "user1", 100L, 20L)));
        Forward forward = new Forward();
        forward.setId(1L);
        forward.setName("我的转发");
        when(forwardService.getById(1L)).thenReturn(forward);
        when(forwardPortService.list(any())).thenReturn(Collections.emptyList());

        FlowRateDto dto = flowRateService.getCurrentRate(10, false);

        verify(tracker).currentRates(eq(10));
        assertEquals("self", dto.getScope());
        assertEquals(120L, dto.getTotalRate());
        assertEquals(1, dto.getActiveForwardCount());
        assertNull(dto.getTopUser());
        assertEquals("我的转发", dto.getTopForward().getForwardName());
        assertNull(dto.getTopForward().getPort());
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
    }

    @Test
    @DisplayName("转发已删除时最忙端口回退为转发ID")
    void topForwardDeleted() {
        when(tracker.currentRates(null)).thenReturn(List.of(
                new RealtimeFlowTracker.ForwardRate(99L, 10, "user1", 10L, 0L)));
        when(forwardService.getById(99L)).thenReturn(null);

        FlowRateDto dto = flowRateService.getCurrentRate(1, true);

        assertEquals("转发#99", dto.getTopForward().getForwardName());
        assertNull(dto.getTopForward().getPort());
    }

    private ForwardPort port(int value) {
        ForwardPort port = new ForwardPort();
        port.setPort(value);
        port.setForwardId(1L);
        port.setNodeId(1L);
        return port;
    }

}

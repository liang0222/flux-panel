package com.admin.common.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时速率滑动窗口测试
 * 覆盖窗口换算、按用户过滤、窗口过期、排序、零流量与清理。
 */
class RealtimeFlowTrackerTest {

    private RealtimeFlowTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RealtimeFlowTracker();
    }

    @Test
    @DisplayName("没有上报时不产生速率")
    void emptyTracker() {
        assertTrue(tracker.currentRates(null).isEmpty());
        assertNull(tracker.lastReportTime());
    }

    @Test
    @DisplayName("按 10 秒窗口把字节数换算为每秒速率")
    void calculateRateFromWindow() {
        // 两次上报，间隔 5 秒：入站共 2000 字节、出站共 400 字节
        tracker.record(1L, 10, "user1", 1000L, 200L, 1_000L);
        tracker.record(1L, 10, "user1", 1000L, 200L, 6_000L);

        List<RealtimeFlowTracker.ForwardRate> rates = tracker.currentRates(null, 6_000L);

        assertEquals(1, rates.size());
        assertEquals(200L, rates.get(0).getInRate());
        assertEquals(40L, rates.get(0).getOutRate());
        assertEquals(240L, rates.get(0).getTotalRate());
        assertEquals(10, rates.get(0).getUserId());
        assertEquals("user1", rates.get(0).getUserName());
    }

    @Test
    @DisplayName("窗口外的旧样本不参与计算")
    void expiredSamplesAreIgnored() {
        tracker.record(1L, 10, "user1", 1000L, 0L, 1_000L);

        // 20 秒后查询，窗口 [10000, 20000] 已不包含该样本
        assertTrue(tracker.currentRates(null, 20_000L).isEmpty());
    }

    @Test
    @DisplayName("按用户过滤，只返回该用户的转发速率")
    void filterByUser() {
        tracker.record(1L, 10, "user1", 1000L, 0L, 1_000L);
        tracker.record(2L, 20, "user2", 3000L, 0L, 1_000L);

        List<RealtimeFlowTracker.ForwardRate> user1 = tracker.currentRates(10, 2_000L);
        assertEquals(1, user1.size());
        assertEquals(1L, user1.get(0).getForwardId());
        assertEquals(100L, user1.get(0).getTotalRate());

        List<RealtimeFlowTracker.ForwardRate> user2 = tracker.currentRates(20, 2_000L);
        assertEquals(1, user2.size());
        assertEquals(2L, user2.get(0).getForwardId());
        assertEquals(300L, user2.get(0).getTotalRate());

        assertTrue(tracker.currentRates(30, 2_000L).isEmpty());
    }

    @Test
    @DisplayName("多个转发按速率降序排列")
    void sortByRateDescending() {
        tracker.record(1L, 10, "user1", 1000L, 0L, 1_000L);
        tracker.record(2L, 10, "user1", 5000L, 0L, 1_000L);
        tracker.record(3L, 10, "user1", 3000L, 0L, 1_000L);

        List<RealtimeFlowTracker.ForwardRate> rates = tracker.currentRates(null, 2_000L);

        assertEquals(3, rates.size());
        assertEquals(2L, rates.get(0).getForwardId());
        assertEquals(3L, rates.get(1).getForwardId());
        assertEquals(1L, rates.get(2).getForwardId());
    }

    @Test
    @DisplayName("零增量上报被忽略，不产生空桶")
    void zeroTrafficIsIgnored() {
        tracker.record(1L, 10, "user1", 0L, 0L, 1_000L);

        assertTrue(tracker.currentRates(null, 2_000L).isEmpty());
    }

    @Test
    @DisplayName("最近上报时间取所有样本的最大值")
    void lastReportTime() {
        tracker.record(1L, 10, "user1", 100L, 0L, 1_000L);
        tracker.record(2L, 10, "user1", 100L, 0L, 3_000L);

        assertEquals(3_000L, tracker.lastReportTime());
    }

    @Test
    @DisplayName("清理超过保留期没有上报的转发")
    void cleanupRemovesStaleBuckets() {
        tracker.record(1L, 10, "user1", 1000L, 0L, 1_000L);
        tracker.record(2L, 10, "user1", 1000L, 0L, 200_000L);

        // 200 秒时清理：转发 1 的最后样本早于 200s-120s=80s，应被移除
        tracker.cleanup(200_000L);

        List<RealtimeFlowTracker.ForwardRate> rates = tracker.currentRates(null, 200_000L);
        assertEquals(1, rates.size());
        assertEquals(2L, rates.get(0).getForwardId());
    }

    @Test
    @DisplayName("按 10 秒窗口计算，多次上报累加")
    void multipleReportsAccumulate() {
        for (int i = 1; i <= 3; i++) {
            tracker.record(1L, 10, "user1", 500L, 100L, 10_000L + i * 1_000L);
        }

        List<RealtimeFlowTracker.ForwardRate> rates = tracker.currentRates(null, 14_000L);

        assertEquals(150L, rates.get(0).getInRate());
        assertEquals(30L, rates.get(0).getOutRate());
        assertEquals(180L, rates.get(0).getTotalRate());
    }

}

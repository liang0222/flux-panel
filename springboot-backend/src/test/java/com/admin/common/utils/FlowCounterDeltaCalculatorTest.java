package com.admin.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转发流量计数器差额计算测试
 * 覆盖首次采集、正常差额、计数器重置、采集缺失与异常值场景。
 */
class FlowCounterDeltaCalculatorTest {

    @Test
    @DisplayName("首次采集只记录基线，不把历史累计值计入区间")
    void firstCollectOnlyCreatesBaseline() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(null, null, 1024L, 2048L);

        assertEquals(0L, delta.getInDelta());
        assertEquals(0L, delta.getOutDelta());
        assertEquals(0L, delta.getTotalDelta());
        assertFalse(delta.isReset());
    }

    @Test
    @DisplayName("正常采集按相邻差额计算新增流量")
    void calculateIncrementBetweenSamples() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(1000L, 2000L, 1300L, 2500L);

        assertEquals(300L, delta.getInDelta());
        assertEquals(500L, delta.getOutDelta());
        assertEquals(800L, delta.getTotalDelta());
        assertFalse(delta.isReset());
    }

    @Test
    @DisplayName("无新增流量时差额为 0")
    void calculateZeroIncrement() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(1000L, 2000L, 1000L, 2000L);

        assertEquals(0L, delta.getTotalDelta());
        assertFalse(delta.isReset());
    }

    @Test
    @DisplayName("计数器重置时按当前值计算，不产生负值")
    void resetCounterProducesCurrentValue() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(5000L, 6000L, 40L, 60L);

        assertEquals(40L, delta.getInDelta());
        assertEquals(60L, delta.getOutDelta());
        assertEquals(100L, delta.getTotalDelta());
        assertTrue(delta.isReset());
        assertTrue(delta.getTotalDelta() >= 0);
    }

    @Test
    @DisplayName("单个方向计数器回退时独立处理，另一个方向仍按差额计算")
    void singleDirectionCounterRollback() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(5000L, 100L, 5200L, 20L);

        assertEquals(200L, delta.getInDelta());
        assertEquals(20L, delta.getOutDelta());
        assertEquals(220L, delta.getTotalDelta());
        assertTrue(delta.isReset());
    }

    @Test
    @DisplayName("采集缺失后恢复时，上次落库值到当前值的差额不会重复累计")
    void missingSamplesAreCoveredByDifference() {
        // 假设上次采集后服务停机，期间累计 900 字节，恢复后第一次采集只应补记 900 字节
        FlowCounterDeltaCalculator.FlowCounterDelta first =
                FlowCounterDeltaCalculator.calculate(1000L, 0L, 1900L, 0L);
        assertEquals(900L, first.getTotalDelta());
        assertFalse(first.isReset());

        // 再次采集时以上一次落库值为基线，不会重复累计同一段流量
        FlowCounterDeltaCalculator.FlowCounterDelta second =
                FlowCounterDeltaCalculator.calculate(1900L, 0L, 2100L, 0L);
        assertEquals(200L, second.getTotalDelta());
    }

    @Test
    @DisplayName("空值与负数按 0 处理，避免出现负流量")
    void nullAndNegativeValuesAreIgnored() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(null, null, null, null);

        assertEquals(0L, delta.getTotalDelta());
        assertFalse(delta.isReset());

        FlowCounterDeltaCalculator.FlowCounterDelta negative =
                FlowCounterDeltaCalculator.calculate(-10L, null, -5L, 100L);

        assertEquals(0L, negative.getInDelta());
        assertEquals(100L, negative.getOutDelta());
        assertEquals(100L, negative.getTotalDelta());
        assertTrue(negative.getTotalDelta() >= 0);
    }

    @Test
    @DisplayName("只有单边历史记录时，缺失方向按 0 基线计算")
    void partialPreviousSample() {
        FlowCounterDeltaCalculator.FlowCounterDelta delta =
                FlowCounterDeltaCalculator.calculate(500L, null, 600L, 200L);

        assertEquals(100L, delta.getInDelta());
        assertEquals(200L, delta.getOutDelta());
        assertEquals(300L, delta.getTotalDelta());
        assertFalse(delta.isReset());
    }

}

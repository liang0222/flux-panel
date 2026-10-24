package com.admin.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 端口流量排行榜统计范围测试
 * 校验“今天”按项目时区从当日零点起算，其余范围按最近 N 个自然日统计。
 */
class FlowStatisticsRangeTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("今天从项目时区的当日零点起算")
    void todayStartsFromLocalMidnight() {
        long now = ZonedDateTime.of(2026, 10, 20, 11, 37, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();

        long start = FlowStatisticsRange.TODAY.resolveStartTime(now, SHANGHAI);

        long expected = ZonedDateTime.of(2026, 10, 20, 0, 0, 0, 0, SHANGHAI)
                .toInstant().toEpochMilli();
        assertEquals(expected, start);
    }

    @Test
    @DisplayName("近 3 天 / 近 7 天 / 近 30 天包含今天在内的自然日")
    void rangesCoverRecentCalendarDays() {
        LocalDate today = LocalDate.of(2026, 10, 20);

        assertEquals(midnightOf(today), FlowStatisticsRange.TODAY.resolveStartTime(today, SHANGHAI));
        assertEquals(midnightOf(today.minusDays(2)),
                FlowStatisticsRange.LAST_3_DAYS.resolveStartTime(today, SHANGHAI));
        assertEquals(midnightOf(today.minusDays(6)),
                FlowStatisticsRange.LAST_7_DAYS.resolveStartTime(today, SHANGHAI));
        assertEquals(midnightOf(today.minusDays(29)),
                FlowStatisticsRange.LAST_30_DAYS.resolveStartTime(today, SHANGHAI));
    }

    @Test
    @DisplayName("跨月边界时近 30 天正确回溯")
    void rangeCrossesMonthBoundary() {
        LocalDate today = LocalDate.of(2026, 3, 2);

        long start = FlowStatisticsRange.LAST_30_DAYS.resolveStartTime(today, SHANGHAI);

        assertEquals(midnightOf(LocalDate.of(2026, 2, 1)), start);
    }

    @Test
    @DisplayName("统计范围解析：缺省为今天，大小写不敏感，非法值返回 null")
    void parseRangeCode() {
        assertEquals(FlowStatisticsRange.TODAY, FlowStatisticsRange.fromCode(null));
        assertEquals(FlowStatisticsRange.TODAY, FlowStatisticsRange.fromCode("  "));
        assertEquals(FlowStatisticsRange.TODAY, FlowStatisticsRange.fromCode("today"));
        assertEquals(FlowStatisticsRange.LAST_3_DAYS, FlowStatisticsRange.fromCode("3d"));
        assertEquals(FlowStatisticsRange.LAST_7_DAYS, FlowStatisticsRange.fromCode("7D"));
        assertEquals(FlowStatisticsRange.LAST_30_DAYS, FlowStatisticsRange.fromCode("30d"));
        assertNull(FlowStatisticsRange.fromCode("1y"));
        assertNotNull(FlowStatisticsRange.TODAY.getLabel());
        assertEquals(30, FlowStatisticsRange.LAST_30_DAYS.getDays());
    }

    private long midnightOf(LocalDate date) {
        return date.atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
    }

}

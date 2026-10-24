package com.admin.common.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 端口流量排行榜统计范围
 * <p>
 * “今天”从项目现有时区规则（JVM 默认时区，部署时通过 -Duser.timezone=Asia/Shanghai 指定）的
 * 当日零点开始计算，其余范围按“含今天在内的最近 N 个自然日”统计：
 * 近 3 天 = 今天 + 前 2 天，近 7 天 = 今天 + 前 6 天，近 30 天 = 今天 + 前 29 天。
 * 结束时间均为当前时刻。
 *
 * @since 2.0.7-beta
 */
public enum FlowStatisticsRange {

    TODAY("today", "今天", 1),
    LAST_3_DAYS("3d", "近 3 天", 3),
    LAST_7_DAYS("7d", "近 7 天", 7),
    LAST_30_DAYS("30d", "近 30 天", 30);

    /**
     * 接口使用的范围标识
     */
    private final String code;

    /**
     * 页面展示名称
     */
    private final String label;

    /**
     * 统计的自然日天数（含今天）
     */
    private final int days;

    FlowStatisticsRange(String code, String label, int days) {
        this.code = code;
        this.label = label;
        this.days = days;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getDays() {
        return days;
    }

    /**
     * 解析统计范围，未指定时默认“今天”
     *
     * @param code 范围标识
     * @return 统计范围，标识非法时返回 null
     */
    public static FlowStatisticsRange fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return TODAY;
        }
        for (FlowStatisticsRange range : values()) {
            if (range.code.equalsIgnoreCase(code.trim())) {
                return range;
            }
        }
        return null;
    }

    /**
     * 计算统计区间开始时间（含）
     *
     * @param nowMillis 当前时间戳（毫秒）
     * @param zone      时区，传 null 时使用 JVM 默认时区
     * @return 区间开始时间戳（毫秒）
     */
    public long resolveStartTime(long nowMillis, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(effectiveZone).toLocalDate();
        return resolveStartTime(today, effectiveZone);
    }

    /**
     * 计算统计区间开始时间（含）
     *
     * @param today 当天日期
     * @param zone  时区，传 null 时使用 JVM 默认时区
     * @return 区间开始时间戳（毫秒）
     */
    public long resolveStartTime(LocalDate today, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate startDate = today.minusDays((long) days - 1);
        return startDate.atStartOfDay(effectiveZone).toInstant().toEpochMilli();
    }

}

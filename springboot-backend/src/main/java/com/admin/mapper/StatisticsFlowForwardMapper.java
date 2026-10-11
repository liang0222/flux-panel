package com.admin.mapper;

import com.admin.entity.StatisticsFlowForward;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 端口流量快照 Mapper。
 *
 * <p>新增功能，不影响原版逻辑。
 */
public interface StatisticsFlowForwardMapper extends BaseMapper<StatisticsFlowForward> {

    /**
     * 取指定转发最近一次的累计流量，用于计算本周期增量。
     */
    StatisticsFlowForward selectLatestByForwardId(@Param("forwardId") Long forwardId);

    /**
     * 按转发聚合排行榜：只统计 created_time >= startTime 的增量并求和，按流量降序。
     *
     * @param startTime 起始时间（毫秒）
     * @param userId    可选，限定某个用户
     * @param limit     返回条数
     */
    List<Map<String, Object>> selectPortRanking(@Param("startTime") Long startTime,
                                                @Param("userId") Long userId,
                                                @Param("limit") Integer limit);
}

package com.admin.mapper;

import com.admin.common.dto.ForwardFlowAggregateDto;
import com.admin.entity.ForwardFlowStatistics;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 端口转发流量采集 Mapper 接口
 * </p>
 *
 * @since 2.0.7-beta
 */
public interface ForwardFlowStatisticsMapper extends BaseMapper<ForwardFlowStatistics> {

    /**
     * 查询指定转发的最后一次采集记录（用于计算增量）
     *
     * @param forwardIds 转发ID集合
     * @return 每个转发的最新一条采集记录
     */
    List<ForwardFlowStatistics> selectLatestByForwardIds(@Param("forwardIds") Collection<Long> forwardIds);

    /**
     * 按转发聚合指定时间区间内的流量增量
     *
     * @param startTime 区间开始（含，毫秒时间戳）
     * @param endTime   区间结束（含，毫秒时间戳）
     * @return 按转发聚合的流量增量
     */
    List<ForwardFlowAggregateDto> selectFlowSumsByRange(@Param("startTime") long startTime,
                                                        @Param("endTime") long endTime);

    /**
     * 查询最近一次采集时间
     *
     * @return 最近采集时间（毫秒时间戳），无数据时返回 null
     */
    Long selectLastCollectTime();

    /**
     * 删除过期采集记录
     *
     * @param cutoffTime 保留截止时间（毫秒时间戳），早于该时间的记录会被删除
     * @return 删除条数
     */
    int deleteBefore(@Param("cutoffTime") long cutoffTime);

}

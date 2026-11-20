package com.admin.mapper;

import com.admin.entity.StatisticsFlow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-08-14
 */
public interface StatisticsFlowMapper extends BaseMapper<StatisticsFlow> {
    int deleteBefore(@Param("cutoffMs") long cutoffMs);

    int insertHourlySnapshot(@Param("label") String label, @Param("createdMs") long createdMs,
                             @Param("hourStartMs") long hourStartMs, @Param("hourEndMs") long hourEndMs);
}

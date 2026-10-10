package com.admin.mapper;

import com.admin.common.dto.PortRankingDto;
import com.admin.entity.StatisticsFlowForward;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 端口（转发）流量快照 Mapper
 * </p>
 *
 * @since 2025-09-28
 */
public interface StatisticsFlowForwardMapper extends BaseMapper<StatisticsFlowForward> {

    /**
     * 按端口聚合流量排行榜。
     *
     * @param startTime 统计起始时间（毫秒时间戳，含）
     * @param userId    非管理员传用户ID做数据隔离；管理员传 null 表示看全部
     * @param limit     返回条数上限
     * @return 按流量降序排列的排行榜
     */
    List<PortRankingDto> selectPortRanking(@Param("startTime") Long startTime,
                                           @Param("userId") Integer userId,
                                           @Param("limit") Integer limit);

    /**
     * 读取指定转发最近一次的累计流量快照，用于计算增量。
     *
     * @param forwardId 转发ID
     * @return 最近一条快照，没有则返回 null
     */
    StatisticsFlowForward selectLatestByForwardId(@Param("forwardId") Long forwardId);

}

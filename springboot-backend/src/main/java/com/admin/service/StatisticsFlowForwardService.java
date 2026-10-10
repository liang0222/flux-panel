package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.StatisticsFlowForward;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 端口（转发）流量快照服务类
 * </p>
 *
 * @since 2025-09-28
 */
public interface StatisticsFlowForwardService extends IService<StatisticsFlowForward> {

    /**
     * 查询端口流量排行榜。
     *
     * @param range  统计区间：today / 3d / 7d
     * @param userId 当前登录用户ID
     * @param roleId 当前登录用户角色ID（0 为管理员）
     * @param limit  返回条数上限
     * @return 统一响应体
     */
    R getPortRanking(String range, Integer userId, Integer roleId, Integer limit);

}

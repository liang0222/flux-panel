package com.admin.service.impl;

import com.admin.entity.StatisticsFlowForward;
import com.admin.mapper.StatisticsFlowForwardMapper;
import com.admin.service.StatisticsFlowForwardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 端口流量快照服务实现。
 *
 * <p>新增功能，不影响原版逻辑。
 */
@Service
public class StatisticsFlowForwardServiceImpl
        extends ServiceImpl<StatisticsFlowForwardMapper, StatisticsFlowForward>
        implements StatisticsFlowForwardService {
}

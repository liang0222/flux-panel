package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.ForwardFlowQueryDto;
import com.admin.common.lang.R;
import com.admin.common.utils.FlowStatisticsRange;
import com.admin.service.ForwardFlowStatisticsService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 端口转发流量排行榜控制器
 * </p>
 * 数据来源为每 5 分钟落库的转发流量采集记录，支持今天 / 近 3 天 / 近 7 天 / 近 30 天四种范围。
 *
 * @since 2.0.7-beta
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward-flow")
public class ForwardFlowController extends BaseController {

    @Resource
    ForwardFlowStatisticsService forwardFlowStatisticsService;

    /**
     * 查询端口转发流量排行榜
     *
     * @param query 统计范围参数：today / 3d / 7d / 30d，缺省为 today
     * @return 排行榜数据
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/ranking")
    public R ranking(@RequestBody(required = false) ForwardFlowQueryDto query) {
        String rangeCode = query == null ? null : query.getRange();
        FlowStatisticsRange range = FlowStatisticsRange.fromCode(rangeCode);
        if (range == null) {
            return R.err("不支持的统计范围：" + rangeCode);
        }
        return R.ok(forwardFlowStatisticsService.getRanking(range));
    }

}

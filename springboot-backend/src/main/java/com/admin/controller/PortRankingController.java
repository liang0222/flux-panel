package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.StatisticsFlowForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * <p>
 * 端口流量排行榜控制器
 * </p>
 * 提供按端口的流量排行，支持「今天 / 三天 / 七天」三种区间。
 * 权限：管理员可查看全部转发；普通用户只能看到自己名下的转发（由服务层按 userId 过滤）。
 *
 * @since 2025-09-28
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/statistics")
public class PortRankingController extends BaseController {

    @Autowired
    private StatisticsFlowForwardService statisticsFlowForwardService;

    /**
     * 端口流量排行榜。
     * 请求体：{ "range": "today" | "3d" | "7d", "limit": 50 }
     */
    @LogAnnotation
    @PostMapping("/port-ranking")
    public R portRanking(@RequestBody(required = false) Map<String, Object> params) {
        Map<String, Object> body = params == null ? java.util.Collections.emptyMap() : params;

        String range = body.get("range") == null ? "today" : body.get("range").toString();

        Integer limit = null;
        Object rawLimit = body.get("limit");
        if (rawLimit != null) {
            try {
                limit = Integer.valueOf(rawLimit.toString());
            } catch (NumberFormatException ignored) {
                // 非法 limit 交由服务层使用默认值
                limit = null;
            }
        }

        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();

        return statisticsFlowForwardService.getPortRanking(range, userId, roleId, limit);
    }

}

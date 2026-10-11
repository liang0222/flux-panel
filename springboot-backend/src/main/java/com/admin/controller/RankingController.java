package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.mapper.StatisticsFlowForwardMapper;
import com.admin.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 端口流量排行榜。
 *
 * <p>数据来源：{@code statistics_flow_forward} 表，由
 * {@code StatisticsFlowForwardAsync} 每 5 分钟采集一次。
 *
 * <p>时间范围支持：今天 / 3 天 / 7 天 / 30 天。
 *
 * <p>本接口为新增功能，不影响原版任何接口。
 */
@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/api/v1/ranking")
public class RankingController extends BaseController {

    /** 排行榜默认返回条数 */
    private static final int DEFAULT_LIMIT = 100;

    /** 最大返回条数，防止一次拉取过多数据 */
    private static final int MAX_LIMIT = 1000;

    @Resource
    private StatisticsFlowForwardMapper statisticsFlowForwardMapper;

    @Resource
    private UserService userService;

    /**
     * 端口流量排行榜。
     *
     * <p>请求体：
     * <pre>
     * { "range": "today" | "3d" | "7d" | "30d", "limit": 100 }
     * </pre>
     *
     * <p>普通用户只能看到自己的转发；管理员可看全部。
     */
    @PostMapping("/port")
    public R portRanking(@RequestBody(required = false) Map<String, Object> params,
                         HttpServletRequest request) {
        try {
            if (params == null) {
                params = new HashMap<>();
            }

            String range = params.get("range") == null ? "today" : String.valueOf(params.get("range"));
            long startTime = resolveStartTime(range);

            int limit = DEFAULT_LIMIT;
            Object limitObj = params.get("limit");
            if (limitObj != null) {
                try {
                    limit = Integer.parseInt(String.valueOf(limitObj));
                } catch (NumberFormatException ignore) {
                    // 非法值就用默认值
                }
            }
            if (limit <= 0) {
                limit = DEFAULT_LIMIT;
            }
            if (limit > MAX_LIMIT) {
                limit = MAX_LIMIT;
            }

            // 判断是否为管理员：管理员看全部，普通用户只看自己
            Long userIdFilter = null;
            Long currentUserId = resolveCurrentUserId(request);
            if (currentUserId != null) {
                User user = userService.getById(currentUserId);
                if (user != null && user.getRoleId() != null && user.getRoleId() != 0) {
                    userIdFilter = currentUserId;
                }
            }

            List<Map<String, Object>> rows =
                    statisticsFlowForwardMapper.selectPortRanking(startTime, userIdFilter, limit);
            if (rows == null) {
                rows = new ArrayList<>();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("range", range);
            result.put("startTime", startTime);
            result.put("list", rows);
            return R.ok(result);
        } catch (Exception e) {
            log.error("端口流量排行榜查询失败: {}", e.getMessage(), e);
            return R.err("排行榜查询失败");
        }
    }

    /**
     * 把时间范围字符串换算成起始毫秒时间戳。
     *
     * <ul>
     *   <li>today —— 今天 00:00:00</li>
     *   <li>3d / 7d / 30d —— 最近 N 天（滚动窗口）</li>
     * </ul>
     * 未知取值一律按 today 处理。
     */
    private long resolveStartTime(String range) {
        if (range == null) {
            range = "today";
        }
        switch (range) {
            case "3d":
                return System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
            case "7d":
                return System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            case "30d":
                return System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
            case "today":
            default:
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                return cal.getTimeInMillis();
        }
    }

    /**
     * 从请求头里解析当前用户ID。
     *
     * <p>拦截器已保证 token 合法；解析失败时返回 null（按管理员口径处理，
     * 因为能走到这里的请求都已通过鉴权）。
     */
    private Long resolveCurrentUserId(HttpServletRequest request) {
        try {
            String token = request.getHeader("Authorization");
            if (token == null || token.isEmpty()) {
                return null;
            }
            return JwtUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            return null;
        }
    }
}

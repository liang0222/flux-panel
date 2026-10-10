package com.admin.service.impl;

import com.admin.common.dto.PortRankingDto;
import com.admin.common.lang.R;
import com.admin.entity.StatisticsFlowForward;
import com.admin.mapper.StatisticsFlowForwardMapper;
import com.admin.service.StatisticsFlowForwardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 端口（转发）流量快照服务实现类
 * </p>
 *
 * @since 2025-09-28
 */
@Service
public class StatisticsFlowForwardServiceImpl
        extends ServiceImpl<StatisticsFlowForwardMapper, StatisticsFlowForward>
        implements StatisticsFlowForwardService {

    /** 管理员角色ID，与 ForwardServiceImpl 保持一致 */
    private static final int ADMIN_ROLE_ID = 0;

    /** 默认返回条数 */
    private static final int DEFAULT_LIMIT = 50;

    /** 最大返回条数，避免一次拉全表 */
    private static final int MAX_LIMIT = 200;

    /**
     * 把外部传入的 range 归一化为受支持的取值。
     * 只接受 today / 3d / 7d，其余（null、空串、拼写错误）一律按 today 处理，
     * 避免非法输入产生「查了今天的数据却回显别的区间」这类不一致。
     *
     * @param range 原始输入
     * @return today / 3d / 7d 之一
     */
    private String normalizeRange(String range) {
        if (range == null) return "today";
        String value = range.trim().toLowerCase();
        if ("3d".equals(value) || "7d".equals(value)) return value;
        return "today";
    }

    /**
     * 统计区间起始时间。
     * 口径为「自然日」：今天 = 今日 00:00 起，三天 = 前 2 天 00:00 起，七天 = 前 6 天 00:00 起。
     * 这样 3 天/7 天都包含今天，符合面板上「今天 / 三天 / 七天」的直觉。
     *
     * @param range 已归一化的区间（today / 3d / 7d）
     * @return 起始毫秒时间戳
     */
    private long resolveStartTime(String range) {
        LocalDate today = LocalDate.now();
        LocalDate startDate;
        if ("3d".equals(range)) {
            startDate = today.minusDays(2);
        } else if ("7d".equals(range)) {
            startDate = today.minusDays(6);
        } else {
            startDate = today;
        }
        return startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public R getPortRanking(String range, Integer userId, Integer roleId, Integer limit) {
        if (userId == null) {
            return R.err("无法获取用户信息");
        }

        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 先把区间归一化，后续过滤与回显都用同一个值，避免二者不一致
        String effectiveRange = normalizeRange(range);
        long startTime = resolveStartTime(effectiveRange);

        // 非管理员只看自己的转发，管理员看全部
        // 注意：roleId 为 Integer、ADMIN_ROLE_ID 为 int，比较时自动拆箱为数值比较；
        // 这里显式用 intValue() 避免 Integer 缓存带来的误读。
        boolean isAdmin = roleId != null && roleId.intValue() == ADMIN_ROLE_ID;
        Integer scopeUserId = isAdmin ? null : userId;

        List<PortRankingDto> list = baseMapper.selectPortRanking(startTime, scopeUserId, size);

        // 回填排名序号，前端可直接展示
        for (int index = 0; index < list.size(); index++) {
            PortRankingDto item = list.get(index);
            if (item.getFlow() == null) {
                item.setFlow(0L);
            }
            item.setRank(index + 1);
            item.setInFlow(item.getFlow());
            item.setOutFlow(0L);
        }

        Map<String, Object> data = new HashMap<>();
        // 回显「实际生效」的区间，而不是原始输入：
        // 未知取值会被按 today 处理，若原样回显会导致前端标题与实际数据不符。
        data.put("range", effectiveRange);
        data.put("startTime", startTime);
        data.put("generatedAt", System.currentTimeMillis());
        data.put("list", list);
        data.put("total", list.size());
        return R.ok(data);
    }

}

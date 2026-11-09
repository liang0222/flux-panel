package com.admin.common.task;

import com.admin.common.dto.*;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CheckGostConfigAsync {

    @Resource
    private NodeService nodeService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private SpeedLimitService speedLimitService;

    @Resource
    TunnelService tunnelService;



    /**
     * 清理孤立的Gost配置项
     */
    @Async
    public void cleanNodeConfigs(String node_id, GostConfigDto gostConfig) {
        Node node = nodeService.getById(node_id);
        if (node != null) {
            cleanOrphanedServices(gostConfig.getServices(), node);
            cleanOrphanedChains(gostConfig.getChains(), node);
            cleanOrphanedLimiters(gostConfig.getLimiters(), node);
        }
    }

    /**
     * 清理孤立的服务
     */
    private void cleanOrphanedServices(List<ConfigItem> configItems, Node node) {
        for (JSONArray services : findOrphanServices(configItems)) {
            GostUtil.DeleteService(node.getId(), services);
            log.info("删除孤立的服务: {} (节点: {})", services, node.getId());
        }
    }

    /**
     * 判定哪些 service 属于「转发/隧道已经不存在」的孤立项。
     * <p>
     * 原实现是「遍历 service → 每个 service 查一次库 → 判定 → 立即删除」；节点每次上报配置都会走这里，
     * 当前生产约 166 个 service，等于每次上报上百条 SELECT。这里改成先把名字里出现的 id 全部收集起来，
     * 用两次 IN 查询拿到真实存在的 id 集合，再逐条判定，查询次数固定为最多两条。
     * <p>
     * 只做判定、不下发删除命令，删除由 {@link #cleanOrphanedServices} 统一执行——
     * 这样判定逻辑可以脱离 WebSocket 单独测试。
     *
     * @return 需要删除的 service 名列表（每个元素是要一起删的 1~2 个名字）
     */
    List<JSONArray> findOrphanServices(List<ConfigItem> configItems) {
        if (configItems == null) return Collections.emptyList();

        // 先按名字把可能涉及的 id 全部收集起来，用两次 IN 查询替代「每个服务一条 SELECT」。
        // 节点每次上报配置都会走这里（当前约 166 个 service），原实现每个 service 都查一次
        // forward/tunnel 表，单次上报就是上百条 SQL；批量后固定为最多两条。
        Set<Long> forwardIds = new HashSet<>();
        Set<Long> tunnelIds = new HashSet<>();
        for (ConfigItem service : configItems) {
            if (Objects.equals(service.getName(), "web_api")) continue;
            List<String> parts = parseServiceName(service.getName());
            if (!hasParts(parts, 2)) continue;
            Long id = parseId(parts.getFirst());
            if (id == null) continue;
            if (Objects.equals(parts.getLast(), "tls")) {
                tunnelIds.add(id);
            } else if (Objects.equals(parts.getLast(), "tcp") && hasParts(parts, 4)) {
                forwardIds.add(id);
            }
        }
        Set<Long> existingForwards = loadExistingIds(forwardIds,
                ids -> forwardService.listByIds(ids).stream().map(Forward::getId).collect(Collectors.toSet()));
        Set<Long> existingTunnels = loadExistingIds(tunnelIds,
                ids -> tunnelService.listByIds(ids).stream().map(Tunnel::getId).collect(Collectors.toSet()));
        if (existingForwards == null || existingTunnels == null) {
            return Collections.emptyList();
        }

        List<JSONArray> orphans = new ArrayList<>();
        for (ConfigItem service : configItems) {
            if (Objects.equals(service.getName(), "web_api")) {
                continue;
            }
            List<String> serviceIds = parseServiceName(service.getName());

            if (Objects.equals(serviceIds.getLast(), "tls") && hasParts(serviceIds, 2)) {
                String forward_id = serviceIds.getFirst();
                Long tunnelId = parseId(forward_id);
                if (tunnelId == null || !existingTunnels.contains(tunnelId)) {
                    JSONArray services = new JSONArray();
                    services.add(forward_id + "_tls");
                    orphans.add(services);
                }
            }

            if (Objects.equals(serviceIds.getLast(), "tcp") && hasParts(serviceIds, 4)) {
                String forward_id = serviceIds.getFirst();
                Long forwardId = parseId(forward_id);
                if (forwardId == null || !existingForwards.contains(forwardId)) {
                    JSONArray services = new JSONArray();
                    services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_tcp");
                    services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_udp");
                    orphans.add(services);
                }
            }
        }
        return orphans;
    }

    /**
     * 批量查出一批 id 中真实存在的那些。
     * 入参为空时直接返回空集合，不发起查询（listByIds 空集合的行为在不同版本间不一致，显式短路更稳）。
     * <p>
     * 查询失败时返回 {@code null}（不是空集合）——调用方据此<b>整轮跳过清理</b>。
     * 这是刻意与「判定为全部不存在」区分开的：若数据库短暂不可用就返回空集合，
     * 所有 service/chain 都会被判成孤立并真的下发删除命令，属于把可恢复的查询故障
     * 放大成不可恢复的误删。原实现查库异常时被 safeExecute 吞掉、该条不删，
     * 这里保持同样的安全边界。
     */
    private Set<Long> loadExistingIds(Set<Long> ids, java.util.function.Function<List<Long>, Set<Long>> loader) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return loader.apply(new ArrayList<>(ids));
        } catch (Exception e) {
            log.warn("批量查询 id 是否存在失败，本轮跳过清理以避免误删", e);
            return null;
        }
    }

    /**
     * 清理孤立的链
     */
    private void cleanOrphanedChains(List<ConfigItem> configItems, Node node) {
        for (String name : findOrphanChains(configItems)) {
            GostUtil.DeleteChains(node.getId(), name);
            log.info("删除孤立的链: {} (节点: {})", name, node.getId());
        }
    }

    /**
     * 判定哪些 chain 属于孤立项（chain 名格式为 {@code chains_<tunnelId>}）。
     * 与 {@link #findOrphanServices} 同理：先把 tunnel id 收集起来批量查一次，再逐条判定。
     * 只做判定不下发删除，便于单独测试。
     */
    List<String> findOrphanChains(List<ConfigItem> configItems) {
        if (configItems == null) return Collections.emptyList();

        Set<Long> tunnelIds = new HashSet<>();
        for (ConfigItem chain : configItems) {
            Long tunnelId = parseId(parseServiceName(chain.getName()).getLast());
            if (tunnelId != null) tunnelIds.add(tunnelId);
        }
        Set<Long> existingTunnels = loadExistingIds(tunnelIds,
                ids -> tunnelService.listByIds(ids).stream().map(Tunnel::getId).collect(Collectors.toSet()));
        if (existingTunnels == null) {
            return Collections.emptyList();
        }

        List<String> orphans = new ArrayList<>();
        for (ConfigItem chain : configItems) {
            Long tunnelId = parseId(parseServiceName(chain.getName()).getLast());
            if (tunnelId == null || !existingTunnels.contains(tunnelId)) {
                orphans.add(chain.getName());
            }
        }
        return orphans;
    }

    /**
     * 清理孤立的限流器
     */
    private void cleanOrphanedLimiters(List<ConfigItem> configItems, Node node) {
        if (configItems == null) return;
        

        for (ConfigItem limiter : configItems) {
            safeExecute(() -> {
                Long limiterId = parseId(limiter.getName());
                // 名字不是纯数字时无法判断归属，跳过而不是拿非法 id 去查库
                if (limiterId == null) return;
                SpeedLimit speedLimit = speedLimitService.getById(limiterId);
                if (speedLimit == null) {
                    GostUtil.DeleteLimiters(node.getId(), limiterId);
                    log.info("删除孤立的限流器: {} (节点: {})", limiter.getName(), node.getId());
                }
            }, "清理限流器 " + limiter.getName());
        }
    }


    /**
     * 安全执行操作，捕获异常
     */
    private void safeExecute(Runnable operation, String operationDesc) {
        try {
            operation.run();
        } catch (Exception e) {
            log.info("执行操作失败: {}", operationDesc, e);
        }
    }


    /**
     * 解析服务名称
     */
    private List<String> parseServiceName(String serviceName) {
        String[] split = serviceName.split("_");
        return new ArrayList<>(Arrays.asList(split));
    }

    /**
     * 服务名是否具备足够的分段数。
     * 服务名格式为 {@code <forwardId>_<userId>_<userTunnelId>_tcp|_udp}（4 段）或 {@code <tunnelId>_tls}（2 段）。
     * 早期实现直接对 split 结果调用 get(1)/get(2)，遇到不含足够下划线的名字会抛
     * IndexOutOfBoundsException（被 safeExecute 吞掉，只留下一条无用的失败日志）。
     */
    private boolean hasParts(List<String> parts, int required) {
        return parts != null && parts.size() >= required;
    }

    /**
     * 把 service/chain 名里的 id 段解析成 Long。
     * 早期实现直接把字符串传给 getById，依赖数据库的隐式类型转换，遇到非数字段（例如
     * 名字不含下划线时 getLast() 返回整个名字）会走到数据库层才报错。这里提前判定。
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

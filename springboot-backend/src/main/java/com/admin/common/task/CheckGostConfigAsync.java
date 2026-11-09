package com.admin.common.task;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
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
import java.util.List;
import java.util.Objects;

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
        if (configItems == null) return;
        for (ConfigItem service : configItems) {
            safeExecute(() -> {

                if (!Objects.equals(service.getName(), "web_api")){
                    List<String> serviceIds = parseServiceName(service.getName());

                    JSONArray services = new JSONArray();
                    if (Objects.equals(serviceIds.getLast(), "tls") && hasParts(serviceIds, 2)){
                        String forward_id = serviceIds.getFirst();
                        services.add(forward_id + "_tls");

                        Long tunnelId = parseId(forward_id);
                        if (tunnelId == null || tunnelService.getById(tunnelId) == null) {
                            GostUtil.DeleteService(node.getId(), services);
                            log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                        }

                    }

                    if (Objects.equals(serviceIds.getLast(), "tcp") && hasParts(serviceIds, 4)){
                        String forward_id = serviceIds.getFirst();
                        services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_tcp");
                        services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_udp");

                        Long forwardId = parseId(forward_id);
                        Forward forward = forwardId == null ? null : forwardService.getById(forwardId);
                        if (forward == null) {
                            GostUtil.DeleteService(node.getId(), services);
                            log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                        }
                    }

                }
            }, "清理服务 " + service.getName());
        }

    }

    /**
     * 清理孤立的链
     */
    private void cleanOrphanedChains(List<ConfigItem> configItems, Node node) {
        if (configItems == null) return;
        for (ConfigItem chain : configItems) {
            safeExecute(() -> {
                List<String>  serviceIds = parseServiceName(chain.getName());
                Long tunnelId = parseId(serviceIds.getLast());
                Tunnel tunnel = tunnelId == null ? null : tunnelService.getById(tunnelId);
                if (tunnel == null) {
                    GostUtil.DeleteChains(node.getId(), chain.getName());
                    log.info("删除孤立的链: {} (节点: {})", chain.getName(), node.getId());
                }
            }, "清理链 " + chain.getName());
        }
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

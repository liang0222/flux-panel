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
                    if (Objects.equals(serviceIds.getLast(), "tls")){
                        String forward_id = serviceIds.getFirst();
                        services.add(forward_id + "_tls");

                        Tunnel tunnel = tunnelService.getById(forward_id);
                        if (tunnel == null) {
                            GostUtil.DeleteService(node.getId(), services);
                            log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                        }

                    }

                    if (Objects.equals(serviceIds.getLast(), "tcp")){
                        String forward_id = serviceIds.getFirst();
                        services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_tcp");
                        services.add(forward_id + "_" + serviceIds.get(1) + "_" + serviceIds.get(2) + "_udp");

                        Forward forward = forwardService.getById(forward_id);
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
                Tunnel tunnel = tunnelService.getById(serviceIds.getLast());
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
                SpeedLimit speedLimit = speedLimitService.getById(limiter.getName());
                if (speedLimit == null) {
                    GostUtil.DeleteLimiters(node.getId(), Long.parseLong(limiter.getName()));
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
}

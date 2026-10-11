package com.admin.common.utils;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.entity.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GostUtil {


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "AddLimiters");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        GostDto gostDto = WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        JSONArray nodes = new JSONArray();
        for (ChainTunnel chainTunnel : chainTunnels) {
            JSONObject dialer = new JSONObject();
            dialer.put("type", chainTunnel.getProtocol());

            JSONObject connector = new JSONObject();
            connector.put("type", "relay");

            Node node_info = node_s.get(chainTunnel.getNodeId());
            JSONObject node = new JSONObject();
            node.put("name", "node_" + chainTunnel.getInx());
            node.put("addr", processServerAddress(node_info.getServerIp() + ":" + chainTunnel.getPort()));
            node.put("connector", connector);
            node.put("dialer", dialer);



            nodes.add(node);
        }
        JSONObject hop = new JSONObject();
        hop.put("name", "hop_" + chainTunnels.getFirst().getTunnelId());

        // interface设置在转发链
        if (StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            hop.put("interface", node_s.get(node_id).getInterfaceName());
        }


        JSONObject selector = new JSONObject();
        selector.put("strategy", chainTunnels.getFirst().getStrategy());
        selector.put("maxFails", 1);
        selector.put("failTimeout", 600000000000L); // 600 秒（纳秒单位）


        hop.put("selector", selector);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", "chains_" + chainTunnels.getFirst().getTunnelId());
        data.put("hops", hops);

        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "AddChains");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteChains");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChainService(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s) {
        JSONArray services = new JSONArray();
        Node node_info = node_s.get(chainTunnel.getNodeId());
        JSONObject service_item = new JSONObject();
        service_item.put("name", chainTunnel.getTunnelId() + "_tls");
        service_item.put("addr", node_info.getTcpListenAddr() + ":" + chainTunnel.getPort());
        
        // 只为出口节点(chainType=3)设置 interface
        if (chainTunnel.getChainType() == 3 && StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", node_s.get(node_id).getInterfaceName());
            service_item.put("metadata", metadata);
        }

        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        if (chainTunnel.getChainType() == 2){
            handler.put("chain","chains_" + chainTunnel.getTunnelId());
        }
        service_item.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", chainTunnel.getProtocol());
        service_item.put("listener", listener);

        services.add(service_item);

        GostDto gostDto = WebSocketServer.send_msg(node_id, services, "AddService");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddAndUpdateService(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel, String meth) {
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            JSONObject service = new JSONObject();
            service.put("name", name + "_" + protocol);
            if (Objects.equals(protocol, "tcp")){
                service.put("addr", node.getTcpListenAddr() + ":" + forwardPort.getPort());
            }else {
                service.put("addr", node.getUdpListenAddr() + ":" + forwardPort.getPort());
            }

            // 只在端口转发时设置 interface（隧道转发时 interface 在转发链的节点上设置）
            if (tunnel.getType() == 1 && StringUtils.isNotBlank(node.getInterfaceName())) {
                JSONObject metadata = new JSONObject();
                metadata.put("interface", node.getInterfaceName());
                service.put("metadata", metadata);
            }

            // 添加限流器配置
            if (limiter != null) {
                service.put("limiter", limiter.toString());
            }

            // 配置处理器
            JSONObject handler = new JSONObject();
            handler.put("type", protocol);
            if (tunnel.getType() == 2){
                handler.put("chain", "chains_" + forward.getTunnelId());
            }
            service.put("handler", handler);

            // 配置监听器
            JSONObject listener = createListener(protocol);
            service.put("listener", listener);

            JSONObject forwarder = createForwarder(forward.getRemoteAddr(), forward.getStrategy());
            service.put("forwarder", forwarder);

            services.add(service);
        }
        GostDto gostDto = WebSocketServer.send_msg(node.getId(), services, meth);
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto DeleteService(Long node_id, JSONArray services) {
        JSONObject data = new JSONObject();
        data.put("services", services);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteService");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto PauseAndResumeService(Long node_id, String name, String meth) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, meth);
    }


    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")) {
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num);
            node.put("addr", addr);
            nodes.add(node);
            num++;
        }

        if (strategy == null || strategy.isEmpty()) {
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    public static String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }

        // 如果已经被方括号包裹，直接返回
        if (serverAddr.startsWith("[")) {
            return serverAddr;
        }

        // 查找最后一个冒号，分离主机和端口
        int lastColonIndex = serverAddr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            // 没有端口号，直接检查是否需要包裹
            return isIPv6Address(serverAddr) ? "[" + serverAddr + "]" : serverAddr;
        }

        String host = serverAddr.substring(0, lastColonIndex);
        String port = serverAddr.substring(lastColonIndex);

        // 检查主机部分是否为IPv6地址
        if (isIPv6Address(host)) {
            return "[" + host + "]" + port;
        }

        return serverAddr;
    }

    private static boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个
        if (!address.contains(":")) {
            return false;
        }

        // 计算冒号数量，IPv6地址至少有2个冒号
        long colonCount = address.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }
}

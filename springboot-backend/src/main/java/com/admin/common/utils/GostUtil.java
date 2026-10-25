package com.admin.common.utils;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.entity.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
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
        return AddChains(node_id, chainTunnels, node_s, WebSocketServer::send_msg);
    }

    public static GostDto AddChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s,
                                    NodeCommandSender sender) {
        JSONObject data = buildChainConfig(node_id, chainTunnels, node_s);

        GostDto gostDto = sender.send(node_id, data, "AddChains");
        String msg = gostDto == null || gostDto.getMsg() == null ? "" : gostDto.getMsg();
        if (!isChainAlreadyExists(msg)) {
            return gostDto;
        }

        // gost 的 AddChains 遇到同名转发链只会返回 “already exists”，不会覆盖节点上的旧配置。
        // 节点侧残留旧链时（删除隧道时节点离线、或隧道 ID 被复用），面板会误以为下发成功，
        // 实际流量仍走旧节点/旧端口 —— 表现为“面板诊断正常、实际端口不通”。
        // 因此这里改为显式下发 UpdateChains，把节点上的旧链覆盖成当前配置。
        log.warn("节点 {} 上已存在转发链 {}，改为下发 UpdateChains 覆盖旧配置", node_id, data.getString("name"));
        JSONObject updateData = new JSONObject();
        updateData.put("chain", data.getString("name"));
        updateData.put("data", data);
        GostDto updateResult = sender.send(node_id, updateData, "UpdateChains");
        String updateMsg = updateResult == null || updateResult.getMsg() == null ? "" : updateResult.getMsg();
        if (isChainNotFound(updateMsg)) {
            // 并发场景下链可能刚被删除，退回新增
            log.warn("节点 {} 上的转发链 {} 已不存在，退回 AddChains", node_id, data.getString("name"));
            return sender.send(node_id, data, "AddChains");
        }
        return updateResult;
    }

    /**
     * 构建转发链配置（纯函数，便于单元测试）
     */
    public static JSONObject buildChainConfig(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        if (chainTunnels == null || chainTunnels.isEmpty()) {
            throw new IllegalArgumentException("转发链节点列表不能为空");
        }
        JSONArray nodes = new JSONArray();
        int index = 0;
        for (ChainTunnel chainTunnel : chainTunnels) {
            JSONObject dialer = new JSONObject();
            dialer.put("type", chainTunnel.getProtocol());

            JSONObject connector = new JSONObject();
            connector.put("type", "relay");

            Node node_info = node_s == null ? null : node_s.get(chainTunnel.getNodeId());
            if (node_info == null) {
                throw new IllegalStateException("转发链引用的节点不存在: " + chainTunnel.getNodeId());
            }
            JSONObject node = new JSONObject();
            // 入口/出口节点的 inx 为空，旧实现会生成重名的 node_null，多个节点时相互覆盖
            node.put("name", buildChainNodeName(index, chainTunnel));
            node.put("addr", processServerAddress(node_info.getServerIp() + ":" + chainTunnel.getPort()));
            node.put("connector", connector);
            node.put("dialer", dialer);

            nodes.add(node);
            index++;
        }
        JSONObject hop = new JSONObject();
        hop.put("name", "hop_" + chainTunnels.getFirst().getTunnelId());

        // interface设置在转发链
        Node currentNode = node_s == null ? null : node_s.get(node_id);
        if (currentNode != null && StringUtils.isNotBlank(currentNode.getInterfaceName())) {
            hop.put("interface", currentNode.getInterfaceName());
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
        return data;
    }

    /**
     * 转发链内节点名，必须唯一：入口/出口的 inx 为空，仅靠 inx 会重名
     */
    public static String buildChainNodeName(int index, ChainTunnel chainTunnel) {
        String suffix = chainTunnel.getInx() == null ? "n" + chainTunnel.getNodeId() : "c" + chainTunnel.getInx();
        return "node_" + index + "_" + suffix;
    }

    public static boolean isChainAlreadyExists(String msg) {
        return msg != null && msg.toLowerCase().contains("already exists");
    }

    public static boolean isChainNotFound(String msg) {
        return msg != null && msg.toLowerCase().contains("not found");
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

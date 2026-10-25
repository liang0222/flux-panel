package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.ChainTunnel;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转发链配置构建测试。
 *
 * <p>重点覆盖：入口/出口节点 inx 为空时的节点命名（旧实现会全部变成 node_null 而互相覆盖）、
 * IPv6 地址加方括号、以及 “already exists” / “not found” 的识别逻辑。</p>
 */
class GostUtilChainConfigTest {

    private Node node(Long id, String name, String serverIp) {
        Node node = new Node();
        node.setId(id);
        node.setName(name);
        node.setServerIp(serverIp);
        return node;
    }

    private ChainTunnel chainTunnel(Long tunnelId, Long nodeId, Integer port, Integer inx, Integer chainType) {
        ChainTunnel chainTunnel = new ChainTunnel();
        chainTunnel.setTunnelId(tunnelId);
        chainTunnel.setNodeId(nodeId);
        chainTunnel.setPort(port);
        chainTunnel.setInx(inx);
        chainTunnel.setChainType(chainType);
        chainTunnel.setProtocol("tls");
        chainTunnel.setStrategy("round");
        return chainTunnel;
    }

    private Map<Long, Node> nodeMap(Node... nodes) {
        Map<Long, Node> map = new HashMap<>();
        for (Node node : nodes) {
            map.put(node.getId(), node);
        }
        return map;
    }

    @Test
    @DisplayName("出口节点（inx 为空）也能生成唯一节点名与 IPv6 地址")
    void buildChainConfigForExitNode() {
        Node exit = node(9L, "exit-hk", "2400:54a0:20c0:300:7::a");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(15L, 9L, 1000, null, 3));

        JSONObject config = GostUtil.buildChainConfig(1L, chainTunnels, nodeMap(exit));

        assertEquals("chains_15", config.getString("name"));
        JSONArray hops = config.getJSONArray("hops");
        assertEquals(1, hops.size());

        JSONObject hop = hops.getJSONObject(0);
        assertEquals("hop_15", hop.getString("name"));
        JSONObject selector = hop.getJSONObject("selector");
        assertEquals("round", selector.getString("strategy"));
        assertEquals(1, selector.getIntValue("maxFails"));
        assertEquals(600000000000L, selector.getLongValue("failTimeout"));

        JSONObject chainNode = hop.getJSONArray("nodes").getJSONObject(0);
        assertEquals("[2400:54a0:20c0:300:7::a]:1000", chainNode.getString("addr"));
        assertEquals("relay", chainNode.getJSONObject("connector").getString("type"));
        assertEquals("tls", chainNode.getJSONObject("dialer").getString("type"));
        assertFalse(chainNode.getString("name").contains("null"), "节点名不能出现 null 后缀");
    }

    @Test
    @DisplayName("同一跳内多个节点名互不重复（旧实现都会是 node_null）")
    void nodeNamesAreUniqueWithinHop() {
        Node a = node(1L, "entry", "10.0.0.1");
        Node b = node(2L, "exit", "10.0.0.2");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(20L, 1L, 2000, null, 1));
        chainTunnels.add(chainTunnel(20L, 2L, 3000, null, 3));

        JSONObject config = GostUtil.buildChainConfig(1L, chainTunnels, nodeMap(a, b));
        JSONArray nodes = config.getJSONArray("hops").getJSONObject(0).getJSONArray("nodes");

        assertEquals(2, nodes.size());
        String firstName = nodes.getJSONObject(0).getString("name");
        String secondName = nodes.getJSONObject(1).getString("name");
        assertNotEquals(firstName, secondName);
        assertEquals("10.0.0.1:2000", nodes.getJSONObject(0).getString("addr"));
        assertEquals("10.0.0.2:3000", nodes.getJSONObject(1).getString("addr"));
    }

    @Test
    @DisplayName("入口节点配置了网卡时写入 hop.interface")
    void interfaceNameIsPropagated() {
        Node entry = node(1L, "entry", "10.0.0.1");
        entry.setInterfaceName("eth1");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(21L, 1L, 2000, null, 1));

        JSONObject config = GostUtil.buildChainConfig(1L, chainTunnels, nodeMap(entry));
        assertEquals("eth1", config.getJSONArray("hops").getJSONObject(0).getString("interface"));
    }

    @Test
    @DisplayName("引用的节点不存在时抛出明确异常，而不是空指针")
    void missingNodeFailsFast() {
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(22L, 404L, 2000, null, 3));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GostUtil.buildChainConfig(1L, chainTunnels, nodeMap(node(1L, "entry", "10.0.0.1"))));
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    @DisplayName("空节点列表直接拒绝")
    void emptyChainTunnelsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GostUtil.buildChainConfig(1L, new ArrayList<>(), new HashMap<>()));
    }

    @Test
    @DisplayName("识别 gost 的 already exists / not found 响应")
    void recognizesGostErrorMessages() {
        assertTrue(GostUtil.isChainAlreadyExists("chain chains_15 already exists"));
        assertTrue(GostUtil.isChainAlreadyExists("Chain chains_15 Already Exists"));
        assertFalse(GostUtil.isChainAlreadyExists("OK"));
        assertFalse(GostUtil.isChainAlreadyExists(null));

        assertTrue(GostUtil.isChainNotFound("chain chains_15 not found"));
        assertFalse(GostUtil.isChainNotFound("OK"));
        assertFalse(GostUtil.isChainNotFound(null));
    }

    private GostDto reply(String msg) {
        GostDto dto = new GostDto();
        dto.setMsg(msg);
        return dto;
    }

    @Test
    @DisplayName("节点已存在同名转发链时改发 UpdateChains 覆盖旧配置")
    void existingChainIsUpdatedInsteadOfIgnored() {
        Node entry = node(1L, "entry", "10.0.0.1");
        Node exit = node(9L, "exit", "10.0.0.9");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(15L, 9L, 1000, null, 3));

        List<String> sentTypes = new ArrayList<>();
        List<Object> sentPayloads = new ArrayList<>();
        NodeCommandSender sender = (nodeId, payload, type) -> {
            sentTypes.add(type);
            sentPayloads.add(payload);
            return "AddChains".equals(type) ? reply("chain chains_15 already exists") : reply("OK");
        };

        GostDto result = GostUtil.AddChains(1L, chainTunnels, nodeMap(entry, exit), sender);

        assertEquals("OK", result.getMsg());
        assertEquals(List.of("AddChains", "UpdateChains"), sentTypes);
        JSONObject updatePayload = (JSONObject) sentPayloads.get(1);
        assertEquals("chains_15", updatePayload.getString("chain"));
        JSONObject updateConfig = updatePayload.getJSONObject("data");
        assertEquals("chains_15", updateConfig.getString("name"));
        assertEquals("hop_15", updateConfig.getJSONArray("hops").getJSONObject(0).getString("name"));
    }

    @Test
    @DisplayName("首次下发成功时不再重复下发 UpdateChains")
    void freshChainIsNotUpdated() {
        Node entry = node(1L, "entry", "10.0.0.1");
        Node exit = node(9L, "exit", "10.0.0.9");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(16L, 9L, 1000, null, 3));

        List<String> sentTypes = Collections.synchronizedList(new ArrayList<>());
        NodeCommandSender sender = (nodeId, payload, type) -> {
            sentTypes.add(type);
            return reply("OK");
        };

        GostUtil.AddChains(1L, chainTunnels, nodeMap(entry, exit), sender);
        assertEquals(List.of("AddChains"), sentTypes);
    }

    @Test
    @DisplayName("UpdateChains 发现链已被删除时退回新增")
    void updateFallsBackToAddWhenChainDisappeared() {
        Node entry = node(1L, "entry", "10.0.0.1");
        Node exit = node(9L, "exit", "10.0.0.9");
        List<ChainTunnel> chainTunnels = new ArrayList<>();
        chainTunnels.add(chainTunnel(17L, 9L, 1000, null, 3));

        List<String> sentTypes = new ArrayList<>();
        NodeCommandSender sender = (nodeId, payload, type) -> {
            sentTypes.add(type);
            if (sentTypes.size() == 1) {
                return reply("chain chains_17 already exists");
            }
            if (sentTypes.size() == 2) {
                return reply("chain chains_17 not found");
            }
            return reply("OK");
        };

        GostDto result = GostUtil.AddChains(1L, chainTunnels, nodeMap(entry, exit), sender);

        assertEquals("OK", result.getMsg());
        assertEquals(List.of("AddChains", "UpdateChains", "AddChains"), sentTypes);
    }
}

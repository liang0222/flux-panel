package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.ChainTunnel;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.service.impl.ForwardServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * get_port 的回归测试。
 *
 * <p>该方法是创建/修改转发时分配入口端口的唯一入口，原先没有对「隧道没有入口节点」
 * 这一前置条件做校验：空列表会让 for 循环不执行，随后 list.getFirst() 抛
 * NoSuchElementException，异常信息无法说明真实原因。本测试锁定两点：
 * <ol>
 *   <li>空列表 / null 时抛出可读的业务异常，而不是 NoSuchElementException；</li>
 *   <li>正常路径行为不变——指定端口、取最小公共端口、各取首个可用端口。</li>
 * </ol>
 */
class ForwardServiceImplGetPortTest {

    private ForwardServiceImpl forwardService;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;

    @BeforeEach
    void setUp() {
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
        ReflectionTestUtils.setField(forwardService, "chainTunnelService", chainTunnelService);
    }

    private static ChainTunnel chainTunnel(long nodeId) {
        ChainTunnel c = new ChainTunnel();
        c.setNodeId(nodeId);
        return c;
    }

    private static ForwardPort usedPort(long forwardId, long nodeId, int port) {
        ForwardPort p = new ForwardPort();
        p.setForwardId(forwardId);
        p.setNodeId(nodeId);
        p.setPort(port);
        return p;
    }

    private static Node node(long id, String portRange) {
        Node n = new Node();
        n.setId(id);
        n.setPort(portRange);
        return n;
    }

    /** getNodePort 依赖 nodeService.getById、chainTunnelService.list 与 forwardPortService.list。 */
    @SuppressWarnings("unchecked")
    private void stubNode(long nodeId, String portRange, List<ForwardPort> occupied) {
        when(nodeService.getById(nodeId)).thenReturn(node(nodeId, portRange));
        when(chainTunnelService.list(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(forwardPortService.list(any(Wrapper.class))).thenReturn(occupied);
    }

    @Test
    @DisplayName("入口节点列表为空时抛出可读异常，而不是 NoSuchElementException")
    void emptyChainTunnelListIsRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> forwardService.get_port(new ArrayList<>(), null, 1L));
        assertTrue(ex.getMessage().contains("没有入口节点"),
                "异常信息应说明是隧道缺入口节点，实际为: " + ex.getMessage());
    }

    @Test
    @DisplayName("入口节点列表为 null 时同样抛出可读异常")
    void nullChainTunnelListIsRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> forwardService.get_port(null, null, 1L));
        assertTrue(ex.getMessage().contains("没有入口节点"),
                "异常信息应说明是隧道缺入口节点，实际为: " + ex.getMessage());
    }

    @Test
    @DisplayName("指定 in_port 且各节点都有该端口时，直接回填并返回")
    void specifiedPortIsAppliedWhenAllNodesHaveIt() {
        stubNode(10L, "1000-1010", List.of());
        List<ChainTunnel> chainTunnels = List.of(chainTunnel(10L));

        List<ChainTunnel> result = forwardService.get_port(chainTunnels, 1005, 1L);

        assertEquals(1005, result.get(0).getPort());
    }

    @Test
    @DisplayName("未指定 in_port 时取各节点端口集合的最小公共端口")
    void smallestCommonPortIsChosen() {
        stubNode(10L, "1000-1010", List.of());
        stubNode(11L, "1004-1014", List.of());
        List<ChainTunnel> chainTunnels = new ArrayList<>(List.of(chainTunnel(10L), chainTunnel(11L)));

        List<ChainTunnel> result = forwardService.get_port(chainTunnels, null, 1L);

        // 10 号节点 1000-1010，11 号节点 1004-1014，交集最小为 1004
        assertEquals(1004, result.get(0).getPort());
        assertEquals(1004, result.get(1).getPort());
    }

    @Test
    @DisplayName("端口已被其他转发占用时跳过，取下一个可用端口")
    void occupiedPortIsSkipped() {
        stubNode(10L, "1000-1002", List.of(usedPort(999L, 10L, 1000)));
        List<ChainTunnel> chainTunnels = List.of(chainTunnel(10L));

        List<ChainTunnel> result = forwardService.get_port(chainTunnels, null, 1L);

        assertEquals(1001, result.get(0).getPort());
    }
}

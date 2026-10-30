package com.admin.service;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.mapper.ForwardMapper;
import com.admin.service.impl.ForwardServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * getAllForwards 批量预加载改造的回归测试。
 *
 * <p>改造前：每条转发在循环内单独 getById/list，N 条转发会产生
 * N(隧道) + N(端口) + N*P(节点) 次查询。
 * 改造后：统一用 listByIds / 一次 in 查询预加载，再在内存中组装。
 *
 * <p>本测试锁定的就是「外部可见结果不变」：入口 IP 逗号串、inPort 兼容字段、
 * 以及隧道缺失 / 端口为空时的跳过语义，都必须与改造前完全一致。
 */
class ForwardServiceImplGetAllForwardsTest {

    private static final String SECRET = "unit-test-secret-key";

    private ForwardServiceImpl forwardService;
    private ForwardMapper forwardMapper;
    private TunnelService tunnelService;
    private ForwardPortService forwardPortService;
    private NodeService nodeService;

    @BeforeEach
    void setUp() {
        // 让 JwtUtil 在无 Spring 容器的情况下可用，并通过 RequestContextHolder
        // 注入一个带 Authorization 头的请求，使 getCurrentUserInfo() 返回管理员。
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", SECRET);
        jwtUtil.init();

        com.admin.entity.User admin = new com.admin.entity.User();
        admin.setId(1L);
        admin.setUser("admin");
        admin.setRoleId(0);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", JwtUtil.generateToken(admin));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        forwardMapper = mock(ForwardMapper.class);
        tunnelService = mock(TunnelService.class);
        forwardPortService = mock(ForwardPortService.class);
        nodeService = mock(NodeService.class);

        forwardService = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwardService, "baseMapper", forwardMapper);
        ReflectionTestUtils.setField(forwardService, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(forwardService, "forwardPortService", forwardPortService);
        ReflectionTestUtils.setField(forwardService, "nodeService", nodeService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ForwardWithTunnelDto dto(long id, Integer tunnelId) {
        ForwardWithTunnelDto d = new ForwardWithTunnelDto();
        d.setId(id);
        d.setName("fwd-" + id);
        d.setTunnelId(tunnelId);
        return d;
    }

    private static Tunnel tunnel(int id, String inIp) {
        Tunnel t = new Tunnel();
        t.setId((long) id);
        t.setInIp(inIp);
        return t;
    }

    private static ForwardPort port(long forwardId, long nodeId, int port) {
        ForwardPort p = new ForwardPort();
        p.setForwardId(forwardId);
        p.setNodeId(nodeId);
        p.setPort(port);
        return p;
    }

    private static Node node(long id, String serverIp) {
        Node n = new Node();
        n.setId(id);
        n.setServerIp(serverIp);
        return n;
    }

    @SuppressWarnings("unchecked")
    private void stubPorts(List<ForwardPort> ports) {
        when(forwardPortService.list(any(Wrapper.class))).thenReturn(ports);
    }

    @SuppressWarnings("unchecked")
    private List<ForwardWithTunnelDto> callAndGet() {
        R r = forwardService.getAllForwards();
        assertNotNull(r);
        return (List<ForwardWithTunnelDto>) r.getData();
    }

    @Test
    @DisplayName("隧道配置了 inIp 时按 IP×端口笛卡尔积生成入口串，inPort 取首个端口")
    void usesTunnelInIpCartesianProduct() {
        when(forwardMapper.selectAllForwardsWithTunnel())
                .thenReturn(new ArrayList<>(List.of(dto(10L, 1))));
        when(tunnelService.listByIds(any(Collection.class)))
                .thenReturn(List.of(tunnel(1, "10.0.0.1, 10.0.0.2")));
        stubPorts(List.of(port(10L, 100L, 8080), port(10L, 100L, 8081)));

        List<ForwardWithTunnelDto> list = callAndGet();

        assertEquals(1, list.size());
        ForwardWithTunnelDto d = list.get(0);
        assertEquals("10.0.0.1:8080,10.0.0.1:8081,10.0.0.2:8080,10.0.0.2:8081", d.getInIp());
        assertEquals(Integer.valueOf(8080), d.getInPort());
    }

    @Test
    @DisplayName("隧道未配置 inIp 时按节点 serverIp 一对一生成入口串")
    void usesNodeServerIpWhenTunnelInIpMissing() {
        when(forwardMapper.selectAllForwardsWithTunnel())
                .thenReturn(new ArrayList<>(List.of(dto(11L, 2))));
        when(tunnelService.listByIds(any(Collection.class)))
                .thenReturn(List.of(tunnel(2, "  ")));
        stubPorts(List.of(port(11L, 200L, 9000), port(11L, 201L, 9001)));
        when(nodeService.listByIds(any(Collection.class)))
                .thenReturn(List.of(node(200L, "192.168.1.10"), node(201L, "192.168.1.11")));

        List<ForwardWithTunnelDto> list = callAndGet();

        assertEquals(1, list.size());
        ForwardWithTunnelDto d = list.get(0);
        assertEquals("192.168.1.10:9000,192.168.1.11:9001", d.getInIp());
        assertEquals(Integer.valueOf(9000), d.getInPort());
    }

    @Test
    @DisplayName("隧道不存在时跳过该转发，且不填充 inIp")
    void skipsForwardWhenTunnelMissing() {
        when(forwardMapper.selectAllForwardsWithTunnel())
                .thenReturn(new ArrayList<>(List.of(dto(12L, 99))));
        when(tunnelService.listByIds(any(Collection.class))).thenReturn(List.of());
        stubPorts(List.of(port(12L, 300L, 7000)));

        List<ForwardWithTunnelDto> list = callAndGet();

        assertEquals(1, list.size());
        assertEquals(null, list.get(0).getInIp());
    }

    @Test
    @DisplayName("没有端口记录时跳过该转发")
    void skipsForwardWhenNoPorts() {
        when(forwardMapper.selectAllForwardsWithTunnel())
                .thenReturn(new ArrayList<>(List.of(dto(13L, 3))));
        when(tunnelService.listByIds(any(Collection.class)))
                .thenReturn(List.of(tunnel(3, "10.1.1.1")));
        stubPorts(List.of());

        List<ForwardWithTunnelDto> list = callAndGet();

        assertEquals(1, list.size());
        assertEquals(null, list.get(0).getInIp());
    }

    @Test
    @DisplayName("多条转发共享隧道与节点时仍能各自正确组装")
    void assemblesMultipleForwardsIndependently() {
        when(forwardMapper.selectAllForwardsWithTunnel())
                .thenReturn(new ArrayList<>(List.of(dto(20L, 4), dto(21L, 4))));
        when(tunnelService.listByIds(any(Collection.class)))
                .thenReturn(List.of(tunnel(4, "10.2.2.2")));
        stubPorts(List.of(
                port(20L, 400L, 1000),
                port(21L, 400L, 2000)
        ));

        List<ForwardWithTunnelDto> list = callAndGet();

        assertEquals(2, list.size());
        assertEquals("10.2.2.2:1000", list.get(0).getInIp());
        assertEquals("10.2.2.2:2000", list.get(1).getInIp());
    }
}

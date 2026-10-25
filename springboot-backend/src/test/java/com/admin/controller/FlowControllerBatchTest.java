package com.admin.controller;

import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ChainTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流量上报批处理优化后的行为等价性测试。
 * <p>
 * 关注三点：
 * 1. 同一批次内相同实体的增量必须合并成一条 UPDATE（聚合优化本身）；
 * 2. 合并写库之后，限额触发时机必须仍然按"处理到该条消息时"的累计值判断（不能提前到第一条）；
 * 3. 管理员的转发（userTunnelId = 0）保持原有语义：不做限额/到期检查。
 */
class FlowControllerBatchTest {

    private static final String SECRET = "node-secret";

    private ForwardService forwardService;
    private TunnelService tunnelService;
    private UserService userService;
    private UserTunnelService userTunnelService;
    private NodeService nodeService;
    private ChainTunnelService chainTunnelService;
    private RecordingController controller;

    /**
     * 覆盖暂停入口，避免测试触碰静态的 GostUtil 下发逻辑，同时记录触发顺序与服务名
     */
    static class RecordingController extends FlowController {
        final List<String> userPauses = new ArrayList<>();
        final List<String> servicePauses = new ArrayList<>();

        @Override
        public void pauseAllUserServices(String userId, String name) {
            userPauses.add(userId + "|" + name);
        }

        @Override
        public void pauseService(List<Forward> forwardList, String name) {
            servicePauses.add(name);
        }
    }

    @BeforeEach
    void setUp() {
        forwardService = mock(ForwardService.class);
        tunnelService = mock(TunnelService.class);
        userService = mock(UserService.class);
        userTunnelService = mock(UserTunnelService.class);
        nodeService = mock(NodeService.class);
        chainTunnelService = mock(ChainTunnelService.class);

        controller = new RecordingController();
        controller.forwardService = forwardService;
        controller.tunnelService = tunnelService;
        controller.userService = userService;
        controller.userTunnelService = userTunnelService;
        controller.nodeService = nodeService;
        controller.chainTunnelService = chainTunnelService;
        controller.realtimeFlowTracker = new RealtimeFlowTracker();

        when(nodeService.existsBySecret(SECRET)).thenReturn(true);
    }

    private static Tunnel tunnel(int flow, String ratio) {
        Tunnel tunnel = new Tunnel();
        tunnel.setId(9L);
        tunnel.setFlow(flow);
        tunnel.setTrafficRatio(new BigDecimal(ratio));
        return tunnel;
    }

    private static Forward forward(long id, int tunnelId, String userName) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setTunnelId(tunnelId);
        forward.setUserName(userName);
        return forward;
    }

    private static User user(long flowGb, long inFlow, long outFlow) {
        User user = new User();
        user.setId(2L);
        user.setFlow(flowGb);
        user.setInFlow(inFlow);
        user.setOutFlow(outFlow);
        user.setStatus(1);
        return user;
    }

    private static UserTunnel userTunnel(int id, long flowGb, long inFlow, long outFlow) {
        UserTunnel userTunnel = new UserTunnel();
        userTunnel.setId(id);
        userTunnel.setUserId(2);
        userTunnel.setTunnelId(9);
        userTunnel.setFlow(flowGb);
        userTunnel.setInFlow(inFlow);
        userTunnel.setOutFlow(outFlow);
        userTunnel.setStatus(1);
        return userTunnel;
    }

    private static String body(String... serviceNames) {
        JSONArray array = new JSONArray();
        for (String name : serviceNames) {
            JSONObject item = new JSONObject();
            item.put("n", name);
            item.put("u", 0L);
            item.put("d", 0L);
            array.add(item);
        }
        return array.toJSONString();
    }

    private static String bodyWithFlowEach(long up, long down, String... serviceNames) {
        JSONArray array = new JSONArray();
        for (String name : serviceNames) {
            JSONObject item = new JSONObject();
            item.put("n", name);
            item.put("u", up);
            item.put("d", down);
            array.add(item);
        }
        return array.toJSONString();
    }

    private static String bodyWithFlow(String serviceName, long up, long down) {
        JSONArray array = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("n", serviceName);
        item.put("u", up);
        item.put("d", down);
        array.add(item);
        return array.toJSONString();
    }

    private static String sqlSet(Wrapper<?> wrapper) {
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> updateWrapper =
                (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) wrapper;
        String sqlSet = updateWrapper.getSqlSet();
        return sqlSet == null ? "" : sqlSet;
    }

    @Test
    @DisplayName("同一批次内同一用户/同一用户隧道只产生一条 UPDATE")
    void aggregatesPerEntityWithinBatch() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 300, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 300, 0));

        controller.uploadFlowData(bodyWithFlowEach(0, 100, "1_2_5", "1_2_5", "1_2_5"), SECRET);

        // 转发、用户、用户隧道各一条（三条消息指向同一实体）
        verify(forwardService, times(1)).update(any(), any());
        verify(userService, times(1)).update(any(), any());
        verify(userTunnelService, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("不同转发、同一用户时，用户维度仍然只写一次")
    void aggregatesUserAcrossDifferentForwards() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 300, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 300, 0));

        controller.uploadFlowData(bodyWithFlowEach(0, 100, "1_2_5", "2_2_5", "3_2_5"), SECRET);

        verify(forwardService, times(3)).update(any(), any());
        verify(userService, times(1)).update(any(), any());
        verify(userTunnelService, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("同一批次内转发与隧道查询各自只查一次")
    void reusesLookupsWithinBatch() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 0, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 0, 0));

        controller.uploadFlowData(body("1_2_5", "1_2_5", "1_2_5", "1_2_5"), SECRET);

        verify(forwardService, times(1)).getById("1");
        verify(tunnelService, times(1)).getById(9);
    }

    @Test
    @DisplayName("已删除的转发在批次内只查一次，且不重复查询")
    void cachesMissingForward() {
        when(forwardService.getById(anyString())).thenReturn(null);
        when(userService.getById(anyString())).thenReturn(user(100, 300, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 300, 0));

        controller.uploadFlowData(bodyWithFlowEach(0, 100, "77_2_5", "77_2_5", "77_2_5"), SECRET);

        verify(forwardService, times(1)).getById("77");
        // 转发不存在时用户/隧道维度仍然记账（与原实现一致）
        verify(userService, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("增量按实体累加后一次性写入")
    @SuppressWarnings("unchecked")
    void accumulatesDeltas() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 0, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 0, 0));

        controller.uploadFlowData(bodyWithFlow("1_2_5", 100, 0), SECRET);
        controller.uploadFlowData(bodyWithFlow("1_2_5", 200, 50), SECRET);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(forwardService, times(2)).update(any(), captor.capture());
        assertTrue(sqlSet(captor.getAllValues().get(0)).contains("out_flow = out_flow + 100"));
        assertTrue(sqlSet(captor.getAllValues().get(1)).contains("out_flow = out_flow + 200"));
        assertTrue(sqlSet(captor.getAllValues().get(1)).contains("in_flow = in_flow + 50"));
    }

    @Test
    @DisplayName("倍率与计费方向仍然生效")
    @SuppressWarnings("unchecked")
    void appliesTrafficRatioAndFlowDirection() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        // 双向计费 + 1.5 倍率：入 100 -> 150*2，出 40 -> 60*2
        when(tunnelService.getById(9)).thenReturn(tunnel(2, "1.5"));
        when(userService.getById(anyString())).thenReturn(user(100, 0, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 0, 0));

        controller.uploadFlowData(bodyWithFlow("1_2_5", 40, 100), SECRET);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(forwardService).update(any(), captor.capture());
        String sql = sqlSet(captor.getValue());
        assertTrue(sql.contains("in_flow = in_flow + 300"), "入站应为 100*1.5*2=300，实际：" + sql);
        assertTrue(sql.contains("out_flow = out_flow + 120"), "出站应为 40*1.5*2=120，实际：" + sql);
    }

    @Test
    @DisplayName("限额触发时机保持逐条语义：由跨过限额的那条消息触发")
    void keepsPerMessageLimitTrigger() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        // 限额 0：任何正增量都会超限；getById 返回写库后的累计值（本批共 +10）
        when(userService.getById(anyString())).thenReturn(user(0, 0, 10));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 10, 0));

        JSONArray array = new JSONArray();
        JSONObject first = new JSONObject();
        first.put("n", "1_2_5");
        first.put("u", 0L);
        first.put("d", 0L);
        JSONObject second = new JSONObject();
        second.put("n", "2_2_5");
        second.put("u", 0L);
        second.put("d", 10L);
        array.add(first);
        array.add(second);

        controller.uploadFlowData(array.toJSONString(), SECRET);

        // 只有第二条（增量 10）才跨过限额，暂停命令必须用第二条的服务名
        assertEquals(1, controller.userPauses.size(), "应当只触发一次用户级暂停");
        assertEquals("2|2_2_5", controller.userPauses.get(0));
    }

    @Test
    @DisplayName("管理员转发（userTunnelId=0）不做限额检查")
    void skipsLimitCheckForAdminForward() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "admin"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(0, 0, 0));

        controller.uploadFlowData(bodyWithFlow("1_1_0", 0, 500), SECRET);

        assertTrue(controller.userPauses.isEmpty(), "管理员转发不应触发用户级暂停");
        verify(userService, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("密钥不存在时直接忽略上报")
    void ignoresUnknownSecret() {
        when(nodeService.existsBySecret(anyString())).thenReturn(false);

        String result = controller.uploadFlowData(body("1_2_5"), "bad-secret");

        assertEquals("ok", result);
        verify(forwardService, never()).update(any(), any());
        verify(forwardService, never()).getById(anyString());
    }

    @Test
    @DisplayName("200 条消息只产生 202 条 UPDATE，而不是 600 条")
    void countsUpdateStatementsPerBatch() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(2, "1.0"));
        when(userService.getById(anyString())).thenReturn(user(100, 20000, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 20000, 0));

        String[] names = new String[200];
        for (int i = 0; i < 200; i++) {
            names[i] = (i + 1) + "_2_5";
        }
        controller.uploadFlowData(bodyWithFlowEach(0, 100, names), SECRET);

        int forwardUpdates = mockingDetails(forwardService).getInvocations().stream()
                .filter(i -> "update".equals(i.getMethod().getName())).toList().size();
        int userUpdates = mockingDetails(userService).getInvocations().stream()
                .filter(i -> "update".equals(i.getMethod().getName())).toList().size();
        int tunnelUpdates = mockingDetails(userTunnelService).getInvocations().stream()
                .filter(i -> "update".equals(i.getMethod().getName())).toList().size();
        int forwardSelects = (int) mockingDetails(forwardService).getInvocations().stream()
                .filter(i -> "getById".equals(i.getMethod().getName())).count();
        int tunnelSelects = (int) mockingDetails(tunnelService).getInvocations().stream()
                .filter(i -> "getById".equals(i.getMethod().getName())).count();
        System.out.println("SELECT_COUNT forward=" + forwardSelects + " tunnel=" + tunnelSelects
                + " total=" + (forwardSelects + tunnelSelects));

        System.out.println("SQL_COUNT forward=" + forwardUpdates
                + " user=" + userUpdates + " tunnel=" + tunnelUpdates
                + " total=" + (forwardUpdates + userUpdates + tunnelUpdates));

        assertEquals(200, forwardUpdates);
        assertEquals(1, userUpdates);
        assertEquals(1, tunnelUpdates);
    }

    @Test
    @DisplayName("零增量不产生 UPDATE 语句")
    void skipsZeroDeltaWrites() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 0, 0));
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 100, 0, 0));

        controller.uploadFlowData(body("1_2_5", "1_2_5"), SECRET);

        verify(forwardService, never()).update(any(), any());
        verify(userService, never()).update(any(), any());
        verify(userTunnelService, never()).update(any(), any());
    }

    @Test
    @DisplayName("web_api 伪服务被跳过")
    void ignoresWebApiEntries() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));

        controller.uploadFlowData(body("web_api"), SECRET);

        verify(forwardService, never()).getById(anyString());
    }

    @Test
    @DisplayName("用户隧道限额用 userTunnelId 对应行的累计值判断")
    void usesUserTunnelAccumulation() {
        when(forwardService.getById(anyString())).thenReturn(forward(1L, 9, "alice"));
        when(tunnelService.getById(9)).thenReturn(tunnel(1, "1"));
        when(userService.getById(anyString())).thenReturn(user(100, 0, 0));
        // 用户隧道限额 0：正增量即超限
        when(userTunnelService.getById(anyString())).thenReturn(userTunnel(5, 0, 0, 0));

        controller.uploadFlowData(bodyWithFlow("1_2_5", 0, 10), SECRET);

        assertEquals(1, controller.servicePauses.size(), "应当触发一次转发级暂停");
        assertEquals("1_2_5", controller.servicePauses.get(0));
        assertTrue(controller.userPauses.isEmpty(), "用户维度未超限不应触发");
    }
}

package com.admin.service;

import com.admin.common.dto.ForwardFlowRankItemDto;
import com.admin.common.dto.ForwardFlowRankingDto;
import com.admin.common.utils.FlowStatisticsRange;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardFlowStatistics;
import com.admin.entity.ForwardPort;
import com.admin.entity.User;
import com.admin.mapper.ForwardFlowStatisticsMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.admin.service.impl.ForwardFlowStatisticsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端口流量采集 / 聚合 / 保留策略的 SQLite 集成测试
 * 校验 schema.sql 自动建表、Mapper SQL、增量落库、区间聚合、排序与历史数据持久化（模拟服务重启）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForwardFlowStatisticsIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String dbFile = Paths.get(System.getProperty("java.io.tmpdir"),
                "flux-flow-test-" + UUID.randomUUID() + ".db").toString();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("jwt-secret", () -> "integration-test-secret");
        registry.add("log-dir", () -> Paths.get(System.getProperty("java.io.tmpdir"), "flux-flow-test-logs").toString());
    }

    @MockBean
    private ForwardService forwardService;

    @MockBean
    private ForwardPortService forwardPortService;

    @MockBean
    private TunnelService tunnelService;

    @Autowired
    private ForwardFlowStatisticsService forwardFlowStatisticsService;

    @Autowired
    private ForwardFlowStatisticsMapper forwardFlowStatisticsMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("采集、区间聚合、排序、清理与重启后可查询")
    void collectAggregateAndPersist() {
        when(forwardPortService.list(any())).thenReturn(List.of(port(1001L, 10L, 25555)));
        when(forwardService.listByIds(any())).thenReturn(Collections.emptyList());
        when(tunnelService.listByIds(any())).thenReturn(Collections.emptyList());

        // 1. 首次采集：建立基线（只记录计数器，不计入增量）
        when(forwardService.list()).thenReturn(List.of(forward(1001L, 1000L, 500L)));
        assertEquals(1, forwardFlowStatisticsService.collectStatistics());
        List<ForwardFlowStatistics> samples = listByForward(1001L);
        assertEquals(1, samples.size());
        assertEquals(0L, samples.get(0).getFlow());
        assertEquals(1500L, samples.get(0).getTotalFlow());
        assertEquals(25555, samples.get(0).getPort());

        // 2. 再次采集：按差额写入新增流量
        when(forwardService.list()).thenReturn(List.of(forward(1001L, 1600L, 700L)));
        assertEquals(1, forwardFlowStatisticsService.collectStatistics());
        samples = listByForward(1001L);
        assertEquals(2, samples.size());
        ForwardFlowStatistics latest = samples.get(1);
        assertEquals(600L, latest.getInFlowDelta());
        assertEquals(200L, latest.getOutFlowDelta());
        assertEquals(800L, latest.getFlow());
        assertEquals(0, latest.getResetFlag());

        // 3. 无流量时不写记录
        assertEquals(0, forwardFlowStatisticsService.collectStatistics());
        assertEquals(2, listByForward(1001L).size());

        // 4. 计数器回退时按重置处理并落库 resetFlag
        when(forwardService.list()).thenReturn(List.of(forward(1001L, 40L, 10L)));
        assertEquals(1, forwardFlowStatisticsService.collectStatistics());
        ForwardFlowStatistics resetSample = listByForward(1001L).get(2);
        assertEquals(40L, resetSample.getInFlowDelta());
        assertEquals(10L, resetSample.getOutFlowDelta());
        assertEquals(1, resetSample.getResetFlag());
        assertTrue(resetSample.getFlow() >= 0);

        // 5. 区间聚合与排序：直接写入不同日期的采集记录
        long now = ZonedDateTime.of(LocalDate.now(SHANGHAI), java.time.LocalTime.of(12, 0), SHANGHAI)
                .toInstant().toEpochMilli();
        long todayMorning = now - 4 * 60 * 60 * 1000L;
        long yesterday = now - 24 * 60 * 60 * 1000L;
        long tenDaysAgo = now - 10L * 24 * 60 * 60 * 1000L;

        insertSample(2001L, "端口A", 30001, todayMorning, 100L, 200L);
        insertSample(2001L, "端口A", 30001, todayMorning + 5 * 60 * 1000L, 50L, 50L);
        insertSample(2002L, "端口B", 30002, todayMorning, 5000L, 5000L);
        insertSample(2003L, "端口C", 30003, yesterday, 999L, 1L);
        insertSample(2004L, "端口D", 30004, tenDaysAgo, 777L, 3L);

        ForwardFlowRankingDto today = forwardFlowStatisticsService
                .getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);
        List<ForwardFlowRankItemDto> todayItems = filterRange(today, 2000L, 3000L);
        assertEquals(2, todayItems.size());
        assertEquals(2002L, todayItems.get(0).getForwardId());
        assertEquals(10000L, todayItems.get(0).getTotalFlow());
        assertEquals(2001L, todayItems.get(1).getForwardId());
        assertEquals(400L, todayItems.get(1).getTotalFlow());
        assertEquals(1, todayItems.get(0).getRank());

        ForwardFlowRankingDto last7Days = forwardFlowStatisticsService
                .getRanking(FlowStatisticsRange.LAST_7_DAYS, now, SHANGHAI);
        List<ForwardFlowRankItemDto> weekItems = filterRange(last7Days, 2000L, 3000L);
        assertEquals(3, weekItems.size());
        assertTrue(weekItems.stream().anyMatch(item -> item.getForwardId() == 2003L));

        ForwardFlowRankingDto last30Days = forwardFlowStatisticsService
                .getRanking(FlowStatisticsRange.LAST_30_DAYS, now, SHANGHAI);
        List<ForwardFlowRankItemDto> monthItems = filterRange(last30Days, 2000L, 3000L);
        assertEquals(4, monthItems.size());
        assertTrue(monthItems.stream().anyMatch(item -> item.getForwardId() == 2004L));
        assertEquals("端口A", monthItems.stream()
                .filter(item -> item.getForwardId() == 2001L).findFirst().orElseThrow().getForwardName());

        // 6. 模拟服务重启：新实例从数据库读取历史，最近采集时间仍可查询
        ForwardFlowStatisticsServiceImpl restarted = new ForwardFlowStatisticsServiceImpl(
                forwardFlowStatisticsMapper, forwardService, forwardPortService, tunnelService);
        Long lastCollectTime = restarted.getLastCollectTime();
        assertNotNull(lastCollectTime);
        ForwardFlowRankingDto afterRestart = restarted.getRanking(FlowStatisticsRange.TODAY, now, SHANGHAI);
        List<ForwardFlowRankItemDto> restartItems = filterRange(afterRestart, 2000L, 3000L);
        assertEquals(2, restartItems.size());
        assertEquals(10000L, restartItems.get(0).getTotalFlow());

        // 7. 清理：只删除保留期之前的数据
        long expired = now - 40L * 24 * 60 * 60 * 1000;
        insertSample(2999L, "过期端口", 39999, expired, 10L, 10L);
        assertNotNull(listByForward(2999L).get(0));
        int deleted = forwardFlowStatisticsService.cleanupExpiredStatistics(
                ForwardFlowStatisticsService.RETENTION_DAYS);
        assertTrue(deleted >= 1);
        assertTrue(listByForward(2999L).isEmpty());
        assertEquals(3, listByForward(1001L).size());
    }

    @Test
    @DisplayName("排行榜接口：管理员可查询，非管理员与未登录被拦截，非法范围返回错误")
    void rankingApiContract() throws Exception {
        insertSample(3001L, "接口端口", 30001, System.currentTimeMillis() - 60_000L, 512L, 256L);

        String adminToken = JwtUtil.generateToken(loginUser(1L, 0));
        String userToken = JwtUtil.generateToken(loginUser(2L, 1));

        // 管理员查询今天范围
        MvcResult okResult = mockMvc.perform(post("/api/v1/forward-flow/ranking")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":\"today\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject okBody = JSONObject.parseObject(okResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, okBody.getIntValue("code"));
        JSONObject data = okBody.getJSONObject("data");
        assertEquals("today", data.getString("range"));
        assertEquals("今天", data.getString("rangeLabel"));
        assertEquals("入站 + 出站", data.getString("trafficScopeLabel"));
        assertEquals(5, data.getIntValue("sampleIntervalMinutes"));
        assertEquals(31, data.getIntValue("retentionDays"));
        assertNotNull(data.getString("startTimeText"));

        JSONArray items = data.getJSONArray("items");
        assertNotNull(items);
        boolean found = false;
        long previousTotal = Long.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            long totalFlow = item.getLongValue("totalFlow");
            // 排行榜必须按总流量降序
            assertTrue(totalFlow <= previousTotal);
            previousTotal = totalFlow;
            assertEquals(i + 1, item.getIntValue("rank"));
            if (item.getLongValue("forwardId") == 3001L) {
                found = true;
                assertEquals(768L, totalFlow);
                assertEquals(30001, item.getIntValue("port"));
                assertEquals("接口端口", item.getString("forwardName"));
            }
        }
        assertTrue(found);

        // 非管理员被权限切面拦截
        MvcResult forbiddenResult = mockMvc.perform(post("/api/v1/forward-flow/ranking")
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":\"today\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject forbiddenBody = JSONObject.parseObject(
                forbiddenResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(403, forbiddenBody.getIntValue("code"));

        // 未登录被 JWT 拦截器拦截
        MvcResult unauthorizedResult = mockMvc.perform(post("/api/v1/forward-flow/ranking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":\"today\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject unauthorizedBody = JSONObject.parseObject(
                unauthorizedResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(401, unauthorizedBody.getIntValue("code"));

        // 非法统计范围
        MvcResult invalidResult = mockMvc.perform(post("/api/v1/forward-flow/ranking")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":\"1y\"}"))
                .andReturn();
        JSONObject invalidBody = JSONObject.parseObject(
                invalidResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(-1, invalidBody.getIntValue("code"));
        assertTrue(invalidBody.getString("msg").contains("不支持的统计范围"));

        // 未传范围时默认今天
        MvcResult defaultResult = mockMvc.perform(post("/api/v1/forward-flow/ranking")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        JSONObject defaultBody = JSONObject.parseObject(
                defaultResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, defaultBody.getIntValue("code"));
        assertEquals("today", defaultBody.getJSONObject("data").getString("range"));
        assertFalse(defaultBody.getJSONObject("data").getJSONArray("items").isEmpty());
    }

    private User loginUser(long id, int roleId) {
        User user = new User();
        user.setId(id);
        user.setUser("tester" + id);
        user.setRoleId(roleId);
        user.setStatus(1);
        return user;
    }

    private List<ForwardFlowStatistics> listByForward(long forwardId) {
        return forwardFlowStatisticsMapper.selectList(
                new QueryWrapper<ForwardFlowStatistics>()
                        .eq("forward_id", forwardId)
                        .orderByAsc("id"));
    }

    private List<ForwardFlowRankItemDto> filterRange(ForwardFlowRankingDto ranking, long minId, long maxId) {
        return ranking.getItems().stream()
                .filter(item -> item.getForwardId() != null
                        && item.getForwardId() >= minId && item.getForwardId() < maxId)
                .collect(Collectors.toList());
    }

    private void insertSample(long forwardId, String name, int port, long createdTime,
                              long inFlowDelta, long outFlowDelta) {
        ForwardFlowStatistics sample = new ForwardFlowStatistics();
        sample.setForwardId(forwardId);
        sample.setForwardName(name);
        sample.setPort(port);
        sample.setPorts(String.valueOf(port));
        sample.setNodeId(10L);
        sample.setTunnelId(5);
        sample.setUserId(2);
        sample.setUserName("user");
        sample.setInFlow(1000L + inFlowDelta);
        sample.setOutFlow(500L + outFlowDelta);
        sample.setInFlowDelta(inFlowDelta);
        sample.setOutFlowDelta(outFlowDelta);
        sample.setFlow(inFlowDelta + outFlowDelta);
        sample.setTotalFlow(1500L + inFlowDelta + outFlowDelta);
        sample.setResetFlag(0);
        sample.setCreatedTime(createdTime);
        sample.setTime("test");
        forwardFlowStatisticsMapper.insert(sample);
    }

    private Forward forward(long id, Long inFlow, Long outFlow) {
        Forward forward = new Forward();
        forward.setId(id);
        forward.setName("端口转发" + id);
        forward.setTunnelId(5);
        forward.setUserId(2);
        forward.setUserName("user");
        forward.setInFlow(inFlow);
        forward.setOutFlow(outFlow);
        forward.setStatus(1);
        return forward;
    }

    private ForwardPort port(long forwardId, long nodeId, int port) {
        ForwardPort forwardPort = new ForwardPort();
        forwardPort.setForwardId(forwardId);
        forwardPort.setNodeId(nodeId);
        forwardPort.setPort(port);
        return forwardPort;
    }

}

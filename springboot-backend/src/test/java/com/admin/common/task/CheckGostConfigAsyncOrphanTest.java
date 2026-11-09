package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定 {@link CheckGostConfigAsync} 的孤立配置判定语义。
 * <p>
 * 这里测的是「判定结果」，不是「有没有真的发出删除命令」——删除动作走 WebSocket，
 * 而判定逻辑已经从执行路径里拆出来（{@code findOrphanServices} / {@code findOrphanChains}），
 * 因此不需要 mock 静态工具类即可覆盖。重点验证三件事：
 * <ol>
 *   <li>批量查询改造后「哪些该删、哪些不该删」与原实现一致；</li>
 *   <li>批量查询只发生一次（改造前是每个 service 一次）；</li>
 *   <li>批量查询失败时返回空结果，本轮一条都不删，不把数据库抖动放大成对节点的大规模误删。</li>
 * </ol>
 */
class CheckGostConfigAsyncOrphanTest {

    private CheckGostConfigAsync task;
    private ForwardService forwardService;
    private TunnelService tunnelService;
    private NodeService nodeService;
    private SpeedLimitService speedLimitService;

    @BeforeEach
    void setUp() {
        task = new CheckGostConfigAsync();
        forwardService = Mockito.mock(ForwardService.class);
        tunnelService = Mockito.mock(TunnelService.class);
        nodeService = Mockito.mock(NodeService.class);
        speedLimitService = Mockito.mock(SpeedLimitService.class);
        ReflectionTestUtils.setField(task, "forwardService", forwardService);
        ReflectionTestUtils.setField(task, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(task, "nodeService", nodeService);
        ReflectionTestUtils.setField(task, "speedLimitService", speedLimitService);
    }

    private static ConfigItem item(String name) {
        ConfigItem c = new ConfigItem();
        c.setName(name);
        return c;
    }

    private static Forward forward(long id) {
        Forward f = new Forward();
        f.setId(id);
        return f;
    }

    private static Tunnel tunnel(long id) {
        Tunnel t = new Tunnel();
        t.setId(id);
        return t;
    }

    /** 存在的转发不判孤立；不存在的转发返回一组 tcp+udp 待删名字。 */
    @Test
    void existingForwardKeptAndMissingForwardDeleted() {
        when(forwardService.listByIds(any())).thenReturn(Collections.singletonList(forward(100L)));
        when(tunnelService.listByIds(any())).thenReturn(new ArrayList<>());

        List<JSONArray> orphans = task.findOrphanServices(
                Arrays.asList(item("100_1_1_tcp"), item("200_1_2_tcp")));

        assertEquals(1, orphans.size(), "只有 200 应被判为孤立");
        JSONArray names = orphans.get(0);
        assertEquals(2, names.size(), "孤立转发要同时删 tcp 与 udp");
        assertTrue(names.contains("200_1_2_tcp"));
        assertTrue(names.contains("200_1_2_udp"));
    }

    /** 批量查询抛异常：返回空列表，一条都不删。 */
    @Test
    void queryFailureYieldsNoOrphans() {
        when(forwardService.listByIds(any())).thenThrow(new RuntimeException("db down"));
        when(tunnelService.listByIds(any())).thenReturn(new ArrayList<>());

        List<JSONArray> orphans = task.findOrphanServices(
                Arrays.asList(item("100_1_1_tcp"), item("200_1_2_tcp")));

        assertTrue(orphans.isEmpty(), "数据库不可用时不能把全部 service 判成孤立");
    }

    /** 50 个 service 只触发一次批量查询，而不是 50 次 getById。 */
    @Test
    void forwardLookupIsBatchedNotPerItem() {
        List<ConfigItem> services = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            services.add(item((i * 100) + "_1_1_tcp"));
        }
        when(forwardService.listByIds(any())).thenReturn(new ArrayList<>());
        when(tunnelService.listByIds(any())).thenReturn(new ArrayList<>());

        List<JSONArray> orphans = task.findOrphanServices(services);

        assertEquals(50, orphans.size());
        verify(forwardService, times(1)).listByIds(any());
        verify(forwardService, never()).getById(any());
    }

    /** 存在的 tunnel 对应的 chain 保留；不存在的判孤立。 */
    @Test
    void existingChainKeptAndMissingChainDeleted() {
        when(tunnelService.listByIds(any())).thenReturn(Collections.singletonList(tunnel(3L)));

        List<String> orphans = task.findOrphanChains(Arrays.asList(item("chains_3"), item("chains_9")));

        assertEquals(Collections.singletonList("chains_9"), orphans);
    }

    /** chain 批量查询失败时同样一条不删。 */
    @Test
    void chainQueryFailureYieldsNoOrphans() {
        when(tunnelService.listByIds(any())).thenThrow(new RuntimeException("db down"));

        assertTrue(task.findOrphanChains(Arrays.asList(item("chains_3"), item("chains_9"))).isEmpty());
    }

    /**
     * 名字不合规时不发起无意义的批量查询，且 {@code web_api} 永远不被判成孤立。
     * <p>
     * 注意 {@code x_tls} 会被判成孤立并删除——这是<b>与改造前一致</b>的行为：
     * id 段不是数字时原实现 {@code parseId} 返回 null，走进 {@code tunnelId == null} 分支直接删。
     * 该名字对应的配置确实无法归属到任何隧道，删掉是既定语义，这里只是把它固定下来，
     * 同时确认这种判定不需要查库（tunnelIds 为空时不发查询）。
     */
    @Test
    void malformedNamesIssueNoQuery() {
        List<JSONArray> orphans = task.findOrphanServices(
                Arrays.asList(item("web_api"), item("weird"), item("x_tls")));

        // web_api 跳过；weird 只有 1 段、长度不足 2，也跳过；只剩 x_tls 被判孤立
        assertEquals(1, orphans.size());
        assertEquals(Collections.singletonList("x_tls"), new ArrayList<>(orphans.get(0)));
        verify(forwardService, never()).listByIds(any());
        verify(tunnelService, never()).listByIds(any());
    }
}

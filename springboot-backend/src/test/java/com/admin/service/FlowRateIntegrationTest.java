package com.admin.service;

import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实时速率端到端测试：节点上报 → 内存滑动窗口 → 接口，含管理员/普通用户范围与鉴权。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FlowRateIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String dbFile = Paths.get(System.getProperty("java.io.tmpdir"),
                "flux-rate-test-" + UUID.randomUUID() + ".db").toString();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("jwt-secret", () -> "rate-test-secret");
        registry.add("log-dir", () -> Paths.get(System.getProperty("java.io.tmpdir"), "flux-rate-test-logs").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NodeService nodeService;

    @Test
    @DisplayName("节点上报后实时速率可查，且按用户隔离")
    void uploadThenQueryRealtimeRate() throws Exception {
        Node node = new Node();
        node.setName("实时速率测试节点");
        node.setSecret("rate-test-node-secret");
        node.setServerIp("127.0.0.1");
        node.setPort("8000-8100");
        node.setStatus(1);
        node.setCreatedTime(System.currentTimeMillis());
        node.setUpdatedTime(System.currentTimeMillis());
        nodeService.save(node);

        // 节点上报：入站 1000、出站 200（服务名：转发_用户_用户隧道）
        upload("rate-test-node-secret", "[{\"n\":\"9101_9001_0\",\"d\":1000,\"u\":200}]");

        String adminToken = JwtUtil.generateToken(user(1L, 0));
        String selfToken = JwtUtil.generateToken(user(9001L, 1));
        String otherToken = JwtUtil.generateToken(user(9999L, 1));

        // 普通用户只看自己：120 B/s（(1000+200)/10）
        JSONObject self = callCurrentRate(selfToken);
        assertEquals("self", self.getString("scope"));
        assertEquals(120L, self.getLongValue("totalRate"));
        assertEquals(100L, self.getLongValue("inRate"));
        assertEquals(20L, self.getLongValue("outRate"));
        assertEquals(1, self.getIntValue("activeForwardCount"));
        assertEquals(10, self.getIntValue("windowSeconds"));
        assertNotNull(self.getLong("sampleTime"));
        JSONObject topForward = self.getJSONObject("topForward");
        assertEquals(9101L, topForward.getLongValue("forwardId"));
        assertEquals("转发#9101", topForward.getString("forwardName"));
        assertTrue(self.get("topUser") == null);

        // 管理员看全部，至少包含刚才的 120 B/s
        JSONObject all = callCurrentRate(adminToken);
        assertEquals("all", all.getString("scope"));
        assertTrue(all.getLongValue("totalRate") >= 120L);
        assertEquals(1, all.getIntValue("activeUserCount"));
        assertNotNull(all.getJSONObject("topUser"));
        assertEquals(9001, all.getJSONObject("topUser").getIntValue("userId"));

        // 其他用户看不到别人的流量
        JSONObject other = callCurrentRate(otherToken);
        assertEquals(0L, other.getLongValue("totalRate"));
        assertEquals(0, other.getIntValue("activeForwardCount"));
        assertTrue(other.get("topForward") == null);

        // 同一窗口内再次上报，速率累加：(1000+500 + 200+100)/10 = 180 B/s
        upload("rate-test-node-secret", "[{\"n\":\"9101_9001_0\",\"d\":500,\"u\":100}]");
        JSONObject afterSecond = callCurrentRate(selfToken);
        assertEquals(180L, afterSecond.getLongValue("totalRate"));
        assertEquals(150L, afterSecond.getLongValue("inRate"));
        assertEquals(30L, afterSecond.getLongValue("outRate"));
    }

    @Test
    @DisplayName("未登录访问实时速率返回 401")
    void unauthorized() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flow-rate/current"))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSONObject.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(401, body.getIntValue("code"));
    }

    @Test
    @DisplayName("非法节点密钥上报被忽略，不产生速率")
    void invalidSecretIsIgnored() throws Exception {
        upload("not-exists-secret", "[{\"n\":\"9201_9002_0\",\"d\":9999,\"u\":9999}]");
        JSONObject data = callCurrentRate(JwtUtil.generateToken(user(9002L, 1)));
        assertEquals(0L, data.getLongValue("totalRate"));
    }

    private void upload(String secret, String body) throws Exception {
        mockMvc.perform(post("/flow/upload")
                        .param("secret", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private JSONObject callCurrentRate(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flow-rate/current")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = JSONObject.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, body.getIntValue("code"));
        return body.getJSONObject("data");
    }

    private User user(long id, int roleId) {
        User user = new User();
        user.setId(id);
        user.setUser("rate-tester" + id);
        user.setRoleId(roleId);
        user.setStatus(1);
        return user;
    }

}

package com.admin.controller;

import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.RealtimeFlowTracker;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FlowReportTransactionTest {
    private static final String NODE_A = "flow-test-node-a";
    private static final String NODE_B = "flow-test-node-b";
    private static final String BATCH = "12345678-abcd-4def-8abc-0123456789ab";
    private static final String BODY = "[{\"n\":\"101_102_103\",\"d\":11,\"u\":4}]";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void fixtureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("flow.db")
                + "?journal_mode=WAL&busy_timeout=5000");
        registry.add("jwt-secret", () -> "fixture-flow-transaction-jwt-only");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    RealtimeFlowTracker tracker;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM flow_report_batch");
        jdbc.update("DELETE FROM forward");
        jdbc.update("DELETE FROM user_tunnel");
        jdbc.update("DELETE FROM user");
        jdbc.update("DELETE FROM tunnel");
        jdbc.update("DELETE FROM node");
        jdbc.update("""
                INSERT INTO node (id, name, secret, server_ip, port, created_time, status)
                VALUES (11, 'a', ?, '127.0.0.1', '9000', 0, 1), (12, 'b', ?, '127.0.0.1', '9001', 0, 1)
                """, NODE_A, NODE_B);
        jdbc.update("""
                INSERT INTO tunnel (id, name, traffic_ratio, type, flow, created_time, updated_time, status)
                VALUES (104, 'tunnel', 1.5, 1, 2, 0, 0, 1)
                """);
        jdbc.update("""
                INSERT INTO user (id, user, pwd, role_id, exp_time, flow, flow_reset_time, num, created_time, status)
                VALUES (102, 'tester', 'fixture-only', 1, ?, ?, 1, 0, 0, 1)
                """, Long.MAX_VALUE, Long.MAX_VALUE);
        jdbc.update("""
                INSERT INTO user_tunnel (id, user_id, tunnel_id, num, flow, flow_reset_time, exp_time, status)
                VALUES (103, 102, 104, 0, ?, 1, ?, 1)
                """, Long.MAX_VALUE, Long.MAX_VALUE);
        jdbc.update("""
                INSERT INTO forward (id, user_id, user_name, name, tunnel_id, remote_addr, created_time, updated_time, status)
                VALUES (101, 102, 'tester', 'forward', 104, '127.0.0.1:9002', 0, 0, 1)
                """);
    }

    @Test
    void normalizesEncryptedRetryAndRejectsChangedAccountingRows() throws Exception {
        expectAck(upload(NODE_A, BATCH, BODY));
        assertCounters(32, 12);
        // A retry remains the same request after JSON reformatting, encryption, or tariff edits.
        jdbc.update("UPDATE tunnel SET traffic_ratio = 2 WHERE id = 104");
        String reordered = "[ { \"u\":4, \"n\":\"101_102_103\", \"d\":11 } ]";
        String encrypted = new JSONObject(Map.of("encrypted", true,
                "data", AESCrypto.create(NODE_A).encrypt(reordered))).toJSONString();
        expectAck(upload(NODE_A, BATCH, encrypted));
        upload(NODE_A, BATCH, "[{\"n\":\"101_102_103\",\"d\":12,\"u\":4}]")
                .andExpect(status().isConflict());
        assertCounters(32, 12);
        assertEquals(1, receiptCount());
        verify(tracker, times(1)).record(101L, 102, "tester", 32L, 12L);

        expectAck(upload(NODE_B, BATCH, BODY));
        assertCounters(76, 28);
        assertEquals(2, receiptCount());
    }

    @Test
    void concurrentRetriesCommitOnlyOneReceiptAndOneIncrement() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Void> retry = () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            expectAck(upload(NODE_A, BATCH, BODY));
            return null;
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(retry);
            var second = pool.submit(retry);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertCounters(32, 12);
        assertEquals(1, receiptCount());
        verify(tracker, times(1)).record(101L, 102, "tester", 32L, 12L);
    }

    @Test
    void counterCapacityFailureRollsBackEarlierWritesAndBatchReceipt() throws Exception {
        jdbc.update("UPDATE tunnel SET traffic_ratio = 1, flow = 1 WHERE id = 104");
        jdbc.update("UPDATE user_tunnel SET in_flow = ?, out_flow = 1 WHERE id = 103", Long.MAX_VALUE - 2);
        String twoBytes = "[{\"n\":\"101_102_103\",\"d\":2}]";
        upload(NODE_A, BATCH, twoBytes).andExpect(status().isUnprocessableEntity());
        assertDirectionCounters("forward", 101, 0, 0);
        assertDirectionCounters("user", 102, 0, 0);
        assertDirectionCounters("user_tunnel", 103, Long.MAX_VALUE - 2, 1);
        assertEquals(0, receiptCount());
        verifyNoInteractions(tracker);

        // Room now exists for this exact batch, ending at the combined long boundary.
        jdbc.update("UPDATE user_tunnel SET out_flow = 0 WHERE id = 103");
        expectAck(upload(NODE_A, BATCH, twoBytes));
        assertDirectionCounters("forward", 101, 2, 0);
        assertDirectionCounters("user", 102, 2, 0);
        assertDirectionCounters("user_tunnel", 103, Long.MAX_VALUE, 0);
        assertEquals(1, receiptCount());
        verify(tracker).record(101L, 102, "tester", 2L, 0L);
    }

    @Test
    void keepsLegacyAcknowledgmentsAndZeroRatioBilling() throws Exception {
        expectAck(upload("unknown-fixture", null, BODY));
        expectAck(upload(NODE_A, null, BODY));
        expectAck(upload(NODE_A, null, BODY));
        assertCounters(64, 24);
        jdbc.update("UPDATE tunnel SET traffic_ratio = 0 WHERE id = 104");
        expectAck(upload(NODE_A, null, BODY));
        assertCounters(64, 24);
        assertEquals(0, receiptCount());
    }

    @Test
    void validatesBatchIdAndInputBeforeAccounting() throws Exception {
        upload(NODE_A, BATCH.toUpperCase(java.util.Locale.ROOT), BODY).andExpect(status().isBadRequest());
        upload("unknown-fixture", BATCH, BODY).andExpect(status().isUnauthorized());
        upload(NODE_A, BATCH, "[{\"n\":\"101_102_103\",\"d\":1.25}]").andExpect(status().isBadRequest());
        assertCounters(0, 0);
        assertEquals(0, receiptCount());
        verifyNoInteractions(tracker);
    }

    private ResultActions upload(String secret, String batchId, String data) throws Exception {
        var request = post("/flow/upload").param("secret", secret)
                .contentType(MediaType.APPLICATION_JSON).content(data);
        if (batchId != null) {
            request.header(FlowController.BATCH_ID_HEADER, batchId);
        }
        return mvc.perform(request);
    }

    private void expectAck(ResultActions response) throws Exception {
        response.andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    private int receiptCount() {
        return jdbc.queryForObject("SELECT count(*) FROM flow_report_batch", Integer.class);
    }

    private void assertCounters(long in, long out) {
        assertDirectionCounters("forward", 101, in, out);
        assertDirectionCounters("user", 102, in, out);
        assertDirectionCounters("user_tunnel", 103, in, out);
    }

    private void assertDirectionCounters(String table, long id, long in, long out) {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + """
                 WHERE id = ? AND in_flow = ? AND out_flow = ?
                   AND typeof(in_flow) = 'integer' AND typeof(out_flow) = 'integer'
                """, Integer.class, id, in, out));
    }
}

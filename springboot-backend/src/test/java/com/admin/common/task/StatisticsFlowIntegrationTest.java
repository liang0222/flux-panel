package com.admin.common.task;

import com.admin.mapper.StatisticsFlowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class StatisticsFlowIntegrationTest {
    private static final long USER_ID = 92001;
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String database = Path.of(System.getProperty("java.io.tmpdir"), "flux-hourly-" + UUID.randomUUID() + ".db").toString();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + database + "?busy_timeout=5000");
    }

    @Autowired private StatisticsFlowMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;

    @Test
    void recordsFirstSampleThenDeltaAndCounterReset() {
        insertUser(100, 20);
        try {
            sample(START);
            setCounters(170, 30);
            sample(START + HOUR);
            setCounters(5, 2);
            sample(START + 2 * HOUR);
            assertEquals(List.of(120L, 80L, 7L), jdbc.queryForList(
                    "SELECT flow FROM statistics_flow WHERE user_id = ? ORDER BY id", Long.class, USER_ID));
            mapper.deleteBefore(START + HOUR);
            assertEquals(List.of(80L, 7L), jdbc.queryForList(
                    "SELECT flow FROM statistics_flow WHERE user_id = ? ORDER BY id", Long.class, USER_ID));
        } finally {
            deleteFixtures();
        }
    }

    @Test
    void twoTransactionsProduceOneSampleInTheSameHour() throws Exception {
        insertUser(11, 4);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); sample(START); });
            var second = executor.submit(() -> { await(start); sample(START); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(15L), jdbc.queryForList(
                    "SELECT flow FROM statistics_flow WHERE user_id = ? AND created_time >= ? AND created_time < ?",
                    Long.class, USER_ID, START, START + HOUR));
        } finally {
            deleteFixtures();
        }
    }

    private void sample(long start) {
        transaction.executeWithoutResult(status -> {
            mapper.deleteBefore(start - 48 * HOUR);
            mapper.insertHourlySnapshot("00:00", start + 1, start, start + HOUR);
        });
    }

    private void insertUser(long in, long out) {
        jdbc.update("INSERT INTO user (id,user,pwd,role_id,exp_time,flow,in_flow,out_flow,flow_reset_time,num,created_time,status) "
                + "VALUES (?, 'hourly-fixture', 'unused', 1, 0, 0, ?, ?, 0, 0, 0, 1)", USER_ID, in, out);
    }

    private void setCounters(long in, long out) {
        jdbc.update("UPDATE user SET in_flow = ?, out_flow = ? WHERE id = ?", in, out, USER_ID);
    }

    private void deleteFixtures() {
        jdbc.update("DELETE FROM statistics_flow");
        jdbc.update("DELETE FROM user WHERE id = ?", USER_ID);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

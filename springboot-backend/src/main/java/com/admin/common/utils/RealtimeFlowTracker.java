package com.admin.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时速率统计器
 * <p>
 * 节点每 5 秒上报一次服务增量（{@code /flow/upload}），这里仅在内存中维护一个滑动窗口，
 * 用最近 {@link #WINDOW_SECONDS} 秒的字节数换算速率，不落库、不影响现有流量累计逻辑。
 * <p>
 * 口径与项目一致：上报值已按隧道倍率与单向/双向计费换算，入站对应上报的 d，出站对应 u。
 * 服务重启后窗口清空，实时速率从零重新累积，这是实时指标的正常行为。
 *
 * @since 2.0.7-beta
 */
@Slf4j
@Component
public class RealtimeFlowTracker {

    /**
     * 速率统计窗口（秒）
     */
    public static final int WINDOW_SECONDS = 10;

    private static final long WINDOW_MS = WINDOW_SECONDS * 1000L;

    /**
     * 样本保留时长，窗口的 12 倍，避免节点短暂中断时立刻丢光历史
     */
    private static final long RETENTION_MS = 2 * 60 * 1000L;

    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 记录一次上报增量
     *
     * @param forwardId 转发ID
     * @param userId    用户ID
     * @param userName  用户名（可为空）
     * @param inBytes   入站字节增量
     * @param outBytes  出站字节增量
     */
    public void record(long forwardId, Integer userId, String userName, long inBytes, long outBytes) {
        record(forwardId, userId, userName, inBytes, outBytes, System.currentTimeMillis());
    }

    /**
     * 记录一次上报增量（指定时间戳，便于测试）
     */
    void record(long forwardId, Integer userId, String userName, long inBytes, long outBytes, long timestamp) {
        if (inBytes <= 0 && outBytes <= 0) {
            return;
        }
        Bucket bucket = buckets.computeIfAbsent(forwardId, key -> new Bucket());
        synchronized (bucket) {
            bucket.userId = userId;
            bucket.userName = userName;
            bucket.samples.addLast(new Sample(timestamp, inBytes, outBytes));
            trim(bucket, timestamp - RETENTION_MS);
        }
    }

    /**
     * 查询当前窗口内的速率
     *
     * @param userId 仅统计指定用户（null 表示全部）
     * @return 按速率降序的转发速率列表
     */
    public List<ForwardRate> currentRates(Integer userId) {
        return currentRates(userId, System.currentTimeMillis());
    }

    /**
     * 查询当前窗口内的速率（指定时间，便于测试）
     */
    List<ForwardRate> currentRates(Integer userId, long now) {
        long windowStart = now - WINDOW_MS;
        List<ForwardRate> rates = new ArrayList<>();
        for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            long inBytes = 0L;
            long outBytes = 0L;
            Integer bucketUserId;
            String bucketUserName;
            synchronized (bucket) {
                bucketUserId = bucket.userId;
                bucketUserName = bucket.userName;
                for (Sample sample : bucket.samples) {
                    if (sample.timestamp >= windowStart) {
                        inBytes += sample.inBytes;
                        outBytes += sample.outBytes;
                    }
                }
            }
            if (userId != null && !userId.equals(bucketUserId)) {
                continue;
            }
            long inRate = bytesToRate(inBytes);
            long outRate = bytesToRate(outBytes);
            if (inRate == 0 && outRate == 0) {
                continue;
            }
            rates.add(new ForwardRate(entry.getKey(), bucketUserId, bucketUserName, inRate, outRate));
        }
        rates.sort((a, b) -> Long.compare(b.getTotalRate(), a.getTotalRate()));
        return rates;
    }

    /**
     * 最近一次收到上报的时间
     */
    public Long lastReportTime() {
        long latest = 0L;
        for (Bucket bucket : buckets.values()) {
            synchronized (bucket) {
                if (!bucket.samples.isEmpty()) {
                    latest = Math.max(latest, bucket.samples.peekLast().timestamp);
                }
            }
        }
        return latest == 0L ? null : latest;
    }

    /**
     * 清理长时间没有上报的转发，避免转发删除后内存残留
     */
    @Scheduled(fixedDelay = 60_000L)
    public void cleanup() {
        cleanup(System.currentTimeMillis());
    }

    /**
     * 清理指定时间点之前不再上报的转发（便于测试）
     */
    void cleanup(long now) {
        long cutoff = now - RETENTION_MS;
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.samples.isEmpty() || bucket.samples.peekLast().timestamp < cutoff;
            }
        });
    }

    private void trim(Bucket bucket, long cutoff) {
        while (!bucket.samples.isEmpty() && bucket.samples.peekFirst().timestamp < cutoff) {
            bucket.samples.removeFirst();
        }
    }

    private long bytesToRate(long bytes) {
        return Math.round(bytes * 1000.0 / WINDOW_MS);
    }

    /**
     * 单个转发的实时速率（字节/秒）
     */
    public static final class ForwardRate {

        private final long forwardId;

        private final Integer userId;

        private final String userName;

        private final long inRate;

        private final long outRate;

        public ForwardRate(long forwardId, Integer userId, String userName, long inRate, long outRate) {
            this.forwardId = forwardId;
            this.userId = userId;
            this.userName = userName;
            this.inRate = inRate;
            this.outRate = outRate;
        }

        public long getForwardId() {
            return forwardId;
        }

        public Integer getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public long getInRate() {
            return inRate;
        }

        public long getOutRate() {
            return outRate;
        }

        public long getTotalRate() {
            return inRate + outRate;
        }
    }

    private static final class Bucket {
        private final Deque<Sample> samples = new ArrayDeque<>();
        private Integer userId;
        private String userName;
    }

    private static final class Sample {
        private final long timestamp;
        private final long inBytes;
        private final long outBytes;

        private Sample(long timestamp, long inBytes, long outBytes) {
            this.timestamp = timestamp;
            this.inBytes = inBytes;
            this.outBytes = outBytes;
        }
    }

}

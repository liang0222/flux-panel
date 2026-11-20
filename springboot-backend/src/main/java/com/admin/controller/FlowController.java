package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.*;
import com.admin.service.ChainTunnelService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 批次去重凭据与各维度的原子计数更新在 SQLite 同一事务提交。
 */
@RestController
@RequestMapping("/flow")
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final BigInteger BYTES_TO_GB = BigInteger.ONE.shiftLeft(30);
    static final String BATCH_ID_HEADER = "X-Flow-Batch-Id";
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    // 缓存加密器实例，避免重复创建。
    // 优化：原实现用 ConcurrentHashMap 且无任何淘汰逻辑，key 是节点 secret，
    // 节点删除/重建后旧 entry 会永久驻留（虽然当前节点数很少、不构成内存风险，
    // 但属于无界增长）。这里改成与 JwtUtil 一致的有界 LRU，上限 64 个节点。
    private static final int CRYPTO_CACHE_SIZE = 64;
    private static final Map<String, AESCrypto> CRYPTO_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, AESCrypto>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AESCrypto> eldest) {
                    return size() > CRYPTO_CACHE_SIZE;
                }
            });

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    @Lazy
    ChainTunnelService chainTunnelService;

    @Resource
    RealtimeFlowTracker realtimeFlowTracker;

    @Resource
    JdbcTemplate jdbcTemplate;

    @Resource
    TransactionTemplate flowTransaction;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getClass().getName());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public ResponseEntity<String> uploadFlowData(@RequestBody String rawData, String secret,
                                                  @RequestHeader(value = BATCH_ID_HEADER, required = false) String batchId) {
        final BatchContext batch;
        try {
            // 无批次 ID 的旧节点沿用原鉴权与 ACK。新协议由验证后的节点主键确定去重范围。
            Node node = batchId == null ? null : nodeService.getOne(new QueryWrapper<Node>()
                    .select("id").eq("secret", secret));
            if (batchId == null && !isValidNode(secret)) {
                return ResponseEntity.ok(SUCCESS_RESPONSE);
            }
            if (batchId != null && node == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid flow node");
            }
            if (batchId != null && !BATCH_ID_PATTERN.matcher(batchId).matches()) {
                return ResponseEntity.badRequest().body("invalid flow batch ID");
            }

            List<FlowEvent> rows = parseFlowRows(rawData, secret);
            if (node == null && rows.isEmpty()) {
                return ResponseEntity.ok(SUCCESS_RESPONSE);
            }
            String payloadHash = node == null ? null : fingerprint(rows);
            batch = flowTransaction.execute(status -> {
                // SQLite 先取得写入资格再读计费配置，避免将已过期的读快照升级为写事务。
                if (node == null) {
                    jdbcTemplate.update("UPDATE flow_report_batch SET received_at = received_at WHERE 0");
                } else if (!registerBatch(node.getId(), batchId, payloadHash)) {
                    return null;
                }

                BatchContext context = new BatchContext();
                for (FlowEvent row : rows) {
                    collectFlowData(row, context);
                }
                flushFlows(context);
                checkLimits(context);
                return context;
            });
        } catch (BatchIdConflict e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("flow batch ID already used");
        } catch (IllegalArgumentException | com.alibaba.fastjson2.JSONException e) {
            return ResponseEntity.badRequest().body("invalid flow data");
        } catch (FlowCounterOverflow e) {
            // 整批回滚，包括批次登记；避免无声丢账或在 SQLite 中把 INTEGER 提升成 REAL。
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("flow counter capacity exceeded");
        } catch (DataAccessException | TransactionException | FlowBillingUnavailable e) {
            log.warn("流量账本事务失败，异常类型: {}", e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("flow accounting unavailable");
        }

        if (batch != null) {
            // 计数提交后才记录速率和发送暂停；重复批次不再次执行这些外部副作用。
            for (FlowEvent event : batch.events) {
                try {
                    recordRealtimeRate(event, batch.forwards.get(event.forwardId));
                } catch (RuntimeException e) {
                    log.warn("账本已提交，记录实时速率失败，异常类型: {}", e.getClass().getSimpleName());
                }
            }
            for (Runnable pause : batch.pauses) {
                try {
                    pause.run();
                } catch (RuntimeException e) {
                    log.warn("账本已提交，暂停流量服务失败，异常类型: {}", e.getClass().getSimpleName());
                }
            }
        }
        return ResponseEntity.ok(SUCCESS_RESPONSE);
    }

    // 保留直接调用的旧签名。
    public String uploadFlowData(String rawData, String secret) {
        return uploadFlowData(rawData, secret, null).getBody();
    }

    private boolean registerBatch(Long nodeId, String batchId, String payloadHash) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO flow_report_batch (node_id, batch_id, payload_sha256, received_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (node_id, batch_id) DO NOTHING
                """, nodeId, batchId, payloadHash, System.currentTimeMillis());
        if (inserted == 1) {
            return true;
        }
        String storedHash = jdbcTemplate.queryForObject("""
                SELECT payload_sha256 FROM flow_report_batch WHERE node_id = ? AND batch_id = ?
                """, String.class, nodeId, batchId);
        if (!payloadHash.equals(storedHash)) {
            throw new BatchIdConflict();
        }
        return false;
    }

    private static String fingerprint(List<FlowEvent> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer record = ByteBuffer.allocate(Long.BYTES * 3 + Integer.BYTES * 2);
            for (FlowEvent row : rows) {
                // 固定宽度，按消息顺序编码原始账本字段，不依赖 JSON 排版、加密封装或当前计费配置。
                record.clear();
                record.putLong(Long.parseLong(row.forwardId)).putInt(Integer.parseInt(row.userId))
                        .putInt(Integer.parseInt(row.userTunnelId)).putLong(row.inDelta).putLong(row.outDelta);
                digest.update(record.array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.debug("流量数据未匹配加密格式或解密失败: {}", e.getClass().getName());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    private List<FlowEvent> parseFlowRows(String rawData, String secret) {
        JSONArray items = JSON.parseArray(decryptIfNeeded(rawData, secret));
        if (items == null) {
            throw new IllegalArgumentException("expected flow array");
        }
        List<FlowEvent> rows = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof JSONObject row) || !(row.get("n") instanceof String serviceName)) {
                throw new IllegalArgumentException("expected named flow row");
            }
            String[] ids = parseServiceName(serviceName);
            if (ids == null) {
                // 节点也会上报 web_api 等非转发服务，它们没有计费实体。
                continue;
            }
            rows.add(new FlowEvent(ids[0], ids[1], ids[2], serviceName,
                    parseByteCount(row.get("d")), parseByteCount(row.get("u"))));
        }
        return rows;
    }

    /** 同批次内复用查询，按既有计费顺序换算后聚合各维度增量。 */
    private void collectFlowData(FlowEvent row, BatchContext batch) {
        long in = row.inDelta;
        long out = row.outDelta;
        Forward forward = resolveForward(batch, row.forwardId);
        if (forward != null) {
            Tunnel tunnel = forward.getTunnelId() == null ? null : resolveTunnel(batch, forward.getTunnelId());
            if (tunnel == null || tunnel.getTrafficRatio() == null || tunnel.getTrafficRatio().signum() < 0
                    || !(Objects.equals(tunnel.getFlow(), 1) || Objects.equals(tunnel.getFlow(), 2))) {
                // 转发存在但计费配置不完整时，不猜测倍率。已删除的转发仍按原值记用户账。
                throw new FlowBillingUnavailable();
            }
            try {
                in = billedBytes(in, tunnel.getTrafficRatio(), tunnel.getFlow());
                out = billedBytes(out, tunnel.getTrafficRatio(), tunnel.getFlow());
            } catch (ArithmeticException e) {
                throw new FlowCounterOverflow();
            }
        }

        accumulate(batch, row.forwardId, row.userId, row.userTunnelId, in, out);
        batch.events.add(new FlowEvent(row.forwardId, row.userId, row.userTunnelId, row.name, in, out));
    }

    private static long parseByteCount(Object value) {
        if (value == null) {
            return 0L;
        }
        long count;
        if (value instanceof Integer || value instanceof Long) {
            count = ((Number) value).longValue();
        } else if (value instanceof BigInteger integer && integer.signum() >= 0 && integer.bitLength() <= 63) {
            count = integer.longValue();
        } else {
            throw new IllegalArgumentException("expected signed-64-bit integer byte count");
        }
        if (count < 0) {
            throw new IllegalArgumentException("expected nonnegative byte count");
        }
        return count;
    }

    private static long billedBytes(long bytes, BigDecimal ratio, int direction) {
        long scaled = ratio.compareTo(BigDecimal.ONE) == 0 ? bytes
                : BigDecimal.valueOf(bytes).multiply(ratio).setScale(0, RoundingMode.DOWN).longValueExact();
        return Math.multiplyExact(scaled, direction);
    }

    /**
     * 同一批次内复用转发查询结果（含"查不到"的结果，避免对已删除转发反复查库）
     */
    private Forward resolveForward(BatchContext batch, String forwardId) {
        if (batch.missingForwards.contains(forwardId)) {
            return null;
        }
        Forward cached = batch.forwards.get(forwardId);
        if (cached != null) {
            return cached;
        }
        Forward forward = forwardService.getById(forwardId);
        if (forward == null) {
            batch.missingForwards.add(forwardId);
        } else {
            batch.forwards.put(forwardId, forward);
        }
        return forward;
    }

    /**
     * 同一批次内复用隧道查询结果
     */
    private Tunnel resolveTunnel(BatchContext batch, Integer tunnelId) {
        if (batch.missingTunnels.contains(tunnelId)) {
            return null;
        }
        Tunnel cached = batch.tunnels.get(tunnelId);
        if (cached != null) {
            return cached;
        }
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            batch.missingTunnels.add(tunnelId);
        } else {
            batch.tunnels.put(tunnelId, tunnel);
        }
        return tunnel;
    }

    private void accumulate(BatchContext batch, String forwardId, String userId, String userTunnelId,
                            long in, long out) {
        try {
            addDelta(batch.forwardDeltas, forwardId, in, out);
            addDelta(batch.userDeltas, userId, in, out);
            if (!DEFAULT_USER_TUNNEL_ID.equals(userTunnelId)) {
                addDelta(batch.tunnelDeltas, userTunnelId, in, out);
            }
        } catch (ArithmeticException e) {
            throw new FlowCounterOverflow();
        }
    }

    private void addDelta(Map<String, long[]> target, String key, long in, long out) {
        long[] sums = target.computeIfAbsent(key, k -> new long[2]);
        sums[0] = Math.addExact(sums[0], in);
        sums[1] = Math.addExact(sums[1], out);
        Math.addExact(sums[0], sums[1]);
    }

    /** 单次 SQL 更新实体并验证累计容量，已删除实体仍沿用原来的无操作语义。 */
    private void flushFlows(BatchContext batch) {
        flushDeltas(forwardService, batch.forwardDeltas);
        flushDeltas(userService, batch.userDeltas);
        flushDeltas(userTunnelService, batch.tunnelDeltas);
    }

    private <T> void flushDeltas(com.baomidou.mybatisplus.extension.service.IService<T> service,
                                 Map<String, long[]> deltas) {
        for (Map.Entry<String, long[]> entry : deltas.entrySet()) {
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            if (in == 0 && out == 0) {
                continue;
            }
            // 历史统计读的是 long 总和；用减法检查两方向累计后仍在该范围。
            // SQLite 的 INTEGER 相加越界会改用 REAL，条件内避免执行越界加法。
            long maxExistingTotal = Long.MAX_VALUE - in - out;
            boolean updated = service.update(null, new UpdateWrapper<T>()
                    .eq("id", entry.getKey())
                    .apply("typeof(COALESCE(in_flow, 0)) = 'integer' AND typeof(COALESCE(out_flow, 0)) = 'integer'")
                    .apply("COALESCE(in_flow, 0) >= 0 AND COALESCE(out_flow, 0) >= 0")
                    .apply("COALESCE(in_flow, 0) <= {0} - COALESCE(out_flow, 0)", maxExistingTotal)
                    // MyBatis-Plus 3.4.1 的 setSql 不支持占位符。这里只拼接已校验的 long。
                    .setSql("in_flow = COALESCE(in_flow, 0) + " + in)
                    .setSql("out_flow = COALESCE(out_flow, 0) + " + out));
            if (!updated && service.count(new QueryWrapper<T>().eq("id", entry.getKey())) > 0) {
                throw new FlowCounterOverflow();
            }
        }
    }

    /**
     * 限额/到期/状态检查。
     * <p>
     * 写库已经按批次聚合，但检查必须仍然按"处理到该条消息时"的累计值判断，
     * 否则原本由第 N 条消息触发的暂停会提前到第 1 条（暂停命令里的服务名会变）。
     * 因此这里用 批次末值 − 本批增量 反推出批次起点，再按消息顺序累加还原。
     */
    private void checkLimits(BatchContext batch) {
        if (batch.events.isEmpty()) {
            return;
        }

        Map<String, User> userSnapshots = new HashMap<>();
        Map<String, Long> userRemaining = totalsById(batch.userDeltas);
        Map<String, Long> tunnelRemaining = totalsById(batch.tunnelDeltas);
        Map<String, UserTunnel> tunnelSnapshots = new HashMap<>();
        long now = System.currentTimeMillis();

        for (FlowEvent event : batch.events) {
            long remaining = userRemaining.compute(event.userId, (key, total) -> total - event.total());
            // 管理员转发计入账本，但该条事件本身不触发限额/到期检查。
            if (DEFAULT_USER_TUNNEL_ID.equals(event.userTunnelId)) {
                continue;
            }

            if (!userSnapshots.containsKey(event.userId)) {
                userSnapshots.put(event.userId, userService.getById(event.userId));
            }
            User user = userSnapshots.get(event.userId);
            if (user != null && (compareToQuota(user.getInFlow(), user.getOutFlow(), remaining, user.getFlow()) > 0
                    || needsPause(user.getExpTime(), user.getStatus(), now))) {
                batch.pauses.add(() -> pauseAllUserServices(event.userId, event.name));
            }

            long tunnelBytesRemaining = tunnelRemaining.compute(event.userTunnelId,
                    (key, total) -> total - event.total());
            if (!tunnelSnapshots.containsKey(event.userTunnelId)) {
                tunnelSnapshots.put(event.userTunnelId, userTunnelService.getById(event.userTunnelId));
            }
            UserTunnel userTunnel = tunnelSnapshots.get(event.userTunnelId);
            if (userTunnel != null && (compareToQuota(userTunnel.getInFlow(), userTunnel.getOutFlow(),
                    tunnelBytesRemaining, userTunnel.getFlow()) >= 0
                    || needsPause(userTunnel.getExpTime(), userTunnel.getStatus(), now))) {
                batch.pauses.add(() -> pauseSpecificForward(userTunnel.getTunnelId(), event.name, event.userId));
            }
        }
    }

    private static int compareToQuota(Long in, Long out, long remaining, Long quotaGb) {
        // 配额或旧快照允许超出 long 的中间范围。空配额不作流量比较，状态和到期检查仍生效。
        if (quotaGb == null) {
            return -1;
        }
        BigInteger current = BigInteger.valueOf(in == null ? 0L : in)
                .add(BigInteger.valueOf(out == null ? 0L : out)).subtract(BigInteger.valueOf(remaining));
        return current.compareTo(BigInteger.valueOf(quotaGb).multiply(BYTES_TO_GB));
    }

    private static boolean needsPause(Long expTime, Integer status, long now) {
        return (expTime != null && expTime <= now) || !Objects.equals(status, 1);
    }

    /** 使用与已提交账本一致的倍率/计费字节。 */
    private void recordRealtimeRate(FlowEvent event, Forward forward) {
        realtimeFlowTracker.record(Long.parseLong(event.forwardId), Integer.parseInt(event.userId),
                forward == null ? null : forward.getUserName(), event.inDelta, event.outDelta);
    }

    private Map<String, Long> totalsById(Map<String, long[]> deltas) {
        Map<String, Long> totals = new HashMap<>();
        deltas.forEach((key, sums) -> totals.put(key, sums[0] + sums[1]));
        return totals;
    }

    /**
     * 单批次采集上下文：查询缓存 + 增量聚合 + 逐条事件（用于还原限额触发时机）
     */
    private static class BatchContext {
        final Map<String, Forward> forwards = new HashMap<>();
        final Set<String> missingForwards = new HashSet<>();
        final Map<Integer, Tunnel> tunnels = new HashMap<>();
        final Set<Integer> missingTunnels = new HashSet<>();
        final Map<String, long[]> forwardDeltas = new LinkedHashMap<>();
        final Map<String, long[]> userDeltas = new LinkedHashMap<>();
        final Map<String, long[]> tunnelDeltas = new LinkedHashMap<>();
        final List<FlowEvent> events = new ArrayList<>();
        final List<Runnable> pauses = new ArrayList<>();
    }

    private static class BatchIdConflict extends RuntimeException {}

    private static class FlowCounterOverflow extends RuntimeException {}

    private static class FlowBillingUnavailable extends RuntimeException {}

    /**
     * 单条上报事件的增量（用于按消息顺序还原累计值）
     */
    private static class FlowEvent {
        final String forwardId;
        final String userId;
        final String userTunnelId;
        final String name;
        final long inDelta;
        final long outDelta;

        FlowEvent(String forwardId, String userId, String userTunnelId, String name, long inDelta, long outDelta) {
            this.forwardId = forwardId;
            this.userId = userId;
            this.userTunnelId = userTunnelId;
            this.name = name;
            this.inDelta = inDelta;
            this.outDelta = outDelta;
        }

        long total() {
            return inDelta + outDelta;
        }
    }

    public void pauseAllUserServices(String userId, String name) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String name, String userId) {
        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel != null && (compareToQuota(userTunnel.getInFlow(), userTunnel.getOutFlow(),
                0L, userTunnel.getFlow()) >= 0 || needsPause(userTunnel.getExpTime(), userTunnel.getStatus(),
                System.currentTimeMillis()))) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
        }
    }

    private void pauseSpecificForward(Integer tunnelId, String name, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void pauseService(List<Forward> forwardList, String name) {
        for (Forward forward : forwardList) {
            List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", forward.getTunnelId()).eq("chain_type", 1));
            for (ChainTunnel chainTunnel : chainTunnels) {
                GostUtil.PauseAndResumeService(chainTunnel.getNodeId(), name, "PauseService");
            }
            // 暂停只改状态，避免把其他批次刚提交的计数覆盖为查询时的旧值。
            forwardService.update(null, new UpdateWrapper<Forward>().eq("id", forward.getId()).set("status", 0));
        }
    }

    private boolean isValidNode(String secret) {
        // 优化：只需要判断存在性，用 LIMIT 1 命中即返回，替代 count(*) 的整表匹配计数
        return nodeService.existsBySecret(secret);
    }

    private String[] parseServiceName(String serviceName) {
        String[] ids = serviceName.split("_", -1);
        if (ids.length != 3 || !SERVICE_ID_PATTERN.matcher(ids[0]).matches()
                || !SERVICE_ID_PATTERN.matcher(ids[1]).matches() || !SERVICE_ID_PATTERN.matcher(ids[2]).matches()) {
            return null;
        }
        if (Long.parseLong(ids[0]) == 0 || Integer.parseInt(ids[1]) == 0) {
            throw new IllegalArgumentException("expected positive forward and user IDs");
        }
        // 管理员自建转发使用 0 作为用户隧道标识。
        Integer.parseInt(ids[2]);
        return ids;
    }
}

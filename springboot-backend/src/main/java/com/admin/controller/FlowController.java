package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.RealtimeFlowTracker;
import com.admin.entity.*;
import com.admin.service.ChainTunnelService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 用于同步相同用户和隧道的流量更新操作
    // 优化：原来按 ID 往 ConcurrentHashMap 里塞锁对象，转发/用户被删除重建后这些 key 永远不会被移除，
    // 内存随历史 ID 数量单调增长；改成固定条带锁，内存恒定，相同 ID 仍然落在同一把锁上。
    private static final int LOCK_STRIPES = 64;
    private static final Object[] USER_LOCKS = new Object[LOCK_STRIPES];
    private static final Object[] TUNNEL_LOCKS = new Object[LOCK_STRIPES];
    private static final Object[] FORWARD_LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            USER_LOCKS[i] = new Object();
            TUNNEL_LOCKS[i] = new Object();
            FORWARD_LOCKS[i] = new Object();
        }
    }

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    @Lazy
    ChainTunnelService chainTunnelService;

    @Resource
    RealtimeFlowTracker realtimeFlowTracker;

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
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
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
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        // 1. 验证节点权限
        if (!isValidNode(secret)) {
            return SUCCESS_RESPONSE;
        }

        // 2. 尝试解密数据
        String decryptedData = decryptIfNeeded(rawData, secret);

        // 3. 解析为FlowDto列表
        JSONArray flowDataList = JSONObject.parseArray(decryptedData);
        // 优化：原来是每次上报都 log.info 整个数组，节点每 5 秒上报一次，等于持续把 JSON 序列化后写盘；
        // 改为按日志级别惰性输出，INFO 下不再产生字符串与 IO 开销。
        if (log.isDebugEnabled()) {
            log.debug("节点上报流量数据{}", flowDataList);
        }

        // 4. 同一批次内复用转发/隧道查询结果，并把增量按实体聚合后再写库
        BatchContext batch = new BatchContext();
        for (int i = 0; i < flowDataList.size(); i++) {
            String jsonObject = flowDataList.getJSONObject(i).toJSONString();
            FlowDto flowDto = JSONObject.parseObject(jsonObject, FlowDto.class);
            if (!Objects.equals(flowDto.getN(), "web_api")) {
                collectFlowData(flowDto, batch);
            }
        }
        flushFlows(batch);
        checkLimits(batch);
        return SUCCESS_RESPONSE;

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
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 单条流量上报的采集逻辑：只做查询（同批次内缓存）、倍率换算、实时速率记录与增量累加，
     * 不直接写库。真正落库在 {@link #flushFlows(BatchContext)} 里按实体聚合后一次性完成。
     */
    private void collectFlowData(FlowDto flowData, BatchContext batch) {
        String[] serviceIds = parseServiceName(flowData.getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = resolveForward(batch, forwardId);
        if (forward != null) {
            Tunnel tunnel = resolveTunnel(batch, forward.getTunnelId());

            // 流量倍率换算
            // 优化：倍率为 1 时 BigDecimal 乘 1 不改变数值，跳过 BigDecimal 的创建与运算（结果完全一致）
            BigDecimal trafficRatio = tunnel.getTrafficRatio();
            long rawD = flowData.getD() == null ? 0L : flowData.getD();
            long rawU = flowData.getU() == null ? 0L : flowData.getU();
            long newD = rawD;
            long newU = rawU;
            if (trafficRatio.compareTo(BigDecimal.ONE) != 0) {
                newD = BigDecimal.valueOf(rawD).multiply(trafficRatio).longValue();
                newU = BigDecimal.valueOf(rawU).multiply(trafficRatio).longValue();
            }

            // 流量方向处理（1=单向 2=双向）
            flowData.setD(newD * tunnel.getFlow());
            flowData.setU(newU * tunnel.getFlow());
        }

        // 记录实时速率（内存滑动窗口，不落库）
        recordRealtimeRate(forwardId, userId, forward, flowData);

        // 按实体累加增量，同一批次内同一个转发/用户/用户隧道只产生一条 UPDATE
        accumulate(batch.forwardDeltas, forwardId, flowData.getD(), flowData.getU());
        accumulate(batch.userDeltas, userId, flowData.getD(), flowData.getU());
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            accumulate(batch.tunnelDeltas, userTunnelId, flowData.getD(), flowData.getU());
        }

        batch.events.add(new FlowEvent(forwardId, userId, userTunnelId,
                buildServiceName(forwardId, userId, userTunnelId), flowData.getD(), flowData.getU()));
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

    private void accumulate(Map<String, long[]> target, String key, long in, long out) {
        long[] sums = target.computeIfAbsent(key, k -> new long[2]);
        sums[0] += in;
        sums[1] += out;
    }

    /**
     * 把本批次聚合后的增量一次性写库。
     * 增量为 0 的实体直接跳过：原来是每条上报都执行一次 UPDATE ... + 0，对结果没有影响。
     */
    private void flushFlows(BatchContext batch) {
        for (Map.Entry<String, long[]> entry : batch.forwardDeltas.entrySet()) {
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            if (in == 0 && out == 0) {
                continue;
            }
            synchronized (getForwardLock(entry.getKey())) {
                forwardService.update(null, new UpdateWrapper<Forward>()
                        .eq("id", entry.getKey())
                        .setSql("in_flow = in_flow + " + in)
                        .setSql("out_flow = out_flow + " + out));
            }
        }
        for (Map.Entry<String, long[]> entry : batch.userDeltas.entrySet()) {
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            if (in == 0 && out == 0) {
                continue;
            }
            synchronized (getUserLock(entry.getKey())) {
                userService.update(null, new UpdateWrapper<User>()
                        .eq("id", entry.getKey())
                        .setSql("in_flow = in_flow + " + in)
                        .setSql("out_flow = out_flow + " + out));
            }
        }
        for (Map.Entry<String, long[]> entry : batch.tunnelDeltas.entrySet()) {
            long in = entry.getValue()[0];
            long out = entry.getValue()[1];
            if (in == 0 && out == 0) {
                continue;
            }
            synchronized (getTunnelLock(entry.getKey())) {
                userTunnelService.update(null, new UpdateWrapper<UserTunnel>()
                        .eq("id", entry.getKey())
                        .setSql("in_flow = in_flow + " + in)
                        .setSql("out_flow = out_flow + " + out));
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
        Map<String, Long> userRunning = new HashMap<>();
        Map<String, Long> tunnelRunning = new HashMap<>();
        Map<String, UserTunnel> tunnelSnapshots = new HashMap<>();

        for (FlowEvent event : batch.events) {
            // 与原实现一致：管理员的转发（userTunnelId = 0）不参与流量限额/到期检查
            if (Objects.equals(event.userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
                continue;
            }

            // 用户维度
            if (!userSnapshots.containsKey(event.userId)) {
                User user = userService.getById(event.userId);
                userSnapshots.put(event.userId, user);
                if (user != null) {
                    long batchTotal = totalOf(batch.userDeltas.get(event.userId));
                    userRunning.put(event.userId, user.getInFlow() + user.getOutFlow() - batchTotal);
                }
            }
            User user = userSnapshots.get(event.userId);
            if (user != null) {
                long current = userRunning.get(event.userId) + event.total();
                userRunning.put(event.userId, current);
                long userFlowLimit = user.getFlow() * BYTES_TO_GB;
                // 与原实现一致：用户维度命中任一条就停止本维度后续判断，但用户隧道维度仍要继续检查
                userCheck:
                {
                    if (userFlowLimit < current) {
                        pauseAllUserServices(event.userId, event.name);
                        break userCheck;
                    }
                    if (user.getExpTime() != null && user.getExpTime() <= System.currentTimeMillis()) {
                        pauseAllUserServices(event.userId, event.name);
                        break userCheck;
                    }
                    if (user.getStatus() != 1) {
                        pauseAllUserServices(event.userId, event.name);
                    }
                }
            }

            // 用户隧道维度
            if (!tunnelSnapshots.containsKey(event.userTunnelId)) {
                UserTunnel userTunnel = userTunnelService.getById(event.userTunnelId);
                tunnelSnapshots.put(event.userTunnelId, userTunnel);
                if (userTunnel != null) {
                    long batchTotal = totalOf(batch.tunnelDeltas.get(event.userTunnelId));
                    tunnelRunning.put(event.userTunnelId,
                            userTunnel.getInFlow() + userTunnel.getOutFlow() - batchTotal);
                }
            }
            UserTunnel userTunnel = tunnelSnapshots.get(event.userTunnelId);
            if (userTunnel == null) {
                continue;
            }
            long current = tunnelRunning.get(event.userTunnelId) + event.total();
            tunnelRunning.put(event.userTunnelId, current);
            if (current >= userTunnel.getFlow() * BYTES_TO_GB) {
                pauseSpecificForward(userTunnel.getTunnelId(), event.name, event.userId);
                continue;
            }
            if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
                pauseSpecificForward(userTunnel.getTunnelId(), event.name, event.userId);
                continue;
            }
            if (userTunnel.getStatus() != 1) {
                pauseSpecificForward(userTunnel.getTunnelId(), event.name, event.userId);
            }
        }
    }

    /**
     * 记录实时速率：使用已按倍率/计费换算后的增量，与流量累计口径一致
     */
    private void recordRealtimeRate(String forwardId, String userId, Forward forward, FlowDto flowData) {
        try {
            long inBytes = flowData.getD() == null ? 0L : flowData.getD();
            long outBytes = flowData.getU() == null ? 0L : flowData.getU();
            realtimeFlowTracker.record(
                    Long.parseLong(forwardId),
                    Integer.parseInt(userId),
                    forward == null ? null : forward.getUserName(),
                    inBytes,
                    outBytes
            );
        } catch (NumberFormatException e) {
            // 服务名异常时不影响正常流量统计
            log.debug("服务名解析失败，跳过实时速率统计: {}", flowData.getN());
        }
    }

    private long totalOf(long[] sums) {
        return sums == null ? 0L : sums[0] + sums[1];
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
    }

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
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() * BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
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
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS[Math.floorMod(userId.hashCode(), LOCK_STRIPES)];
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS[Math.floorMod(userTunnelId.hashCode(), LOCK_STRIPES)];
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS[Math.floorMod(forwardId.hashCode(), LOCK_STRIPES)];
    }

    private boolean isValidNode(String secret) {
        // 优化：只需要判断存在性，用 LIMIT 1 命中即返回，替代 count(*) 的整表匹配计数
        return nodeService.existsBySecret(secret);
    }

    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}

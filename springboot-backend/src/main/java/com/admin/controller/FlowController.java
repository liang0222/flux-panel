package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> FORWARD_LOCKS = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

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
        FlowDto flowDataList = JSONObject.parseObject(decryptedData, FlowDto.class);
        if (Objects.equals(flowDataList.getN(), "web_api")) {
            return SUCCESS_RESPONSE;
        }

        // 记录日志：这是节点每几秒一次的【高频上报】路径，
        // 若用 info 级别会在生产环境刷出巨量日志（既拖慢上报又淹没有用信息），故降为 debug。
        log.debug("节点上报流量数据{}", flowDataList);
        // 4. 处理流量数据
        return processFlowData(flowDataList);
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
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(FlowDto flowDataList) {
        String[] serviceIds = parseServiceName(flowDataList.getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = forwardService.getById(forwardId);

        // 获取流量计费类型
        int flowType = getFlowType(forward);

        //  处理流量倍率及单双向计算
        FlowDto flowStats = filterFlowData(flowDataList, forward, flowType);

        // 先更新所有流量统计 - 确保流量数据的一致性
        updateForwardFlow(forwardId, flowStats);
        updateUserFlow(userId, flowStats);
        updateUserTunnelFlow(userTunnelId, flowStats);

        // 7. 检查和服务暂停操作
        String name = buildServiceName(forwardId, userId, userTunnelId);
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) { // 非管理员的转发需要检测流量限制
            checkUserRelatedLimits(userId, name);
            checkUserTunnelRelatedLimits(userTunnelId, name, userId);
        }

        return SUCCESS_RESPONSE;
    }

    private void checkUserRelatedLimits(String userId, String name) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId, name);
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
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
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
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null){
                GostUtil.PauseService(tunnel.getInNodeId(), name);
                if (tunnel.getType() == 2){
                    GostUtil.PauseRemoteService(tunnel.getOutNodeId(), name);
                }
            }
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    private FlowDto filterFlowData(FlowDto flowDto, Forward forward, int flowType) {
        // u/d 为空时不能直接参与运算：
        //   - Long 为 null 时 BigDecimal.valueOf(...) 会自动拆箱抛 NPE；
        //   - 即使不抛，null 也会让后续 setSql 拼出 `in_flow = in_flow + null`。
        long rawD = flowDto.getD() == null ? 0L : flowDto.getD();
        long rawU = flowDto.getU() == null ? 0L : flowDto.getU();

        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                // traffic_ratio 列允许为 NULL（历史数据或被直接改库时），
                // 直接 multiply(null) 会抛 NPE，导致该节点流量统计静默失效。
                BigDecimal trafficRatio = tunnel.getTrafficRatio();
                if (trafficRatio == null) {
                    trafficRatio = BigDecimal.ONE;
                }

                BigDecimal newD = BigDecimal.valueOf(rawD).multiply(trafficRatio);
                BigDecimal newU = BigDecimal.valueOf(rawU).multiply(trafficRatio);

                rawD = newD.longValue() * flowType;
                rawU = newU.longValue() * flowType;
            }
        }

        flowDto.setD(rawD);
        flowDto.setU(rawU);
        return flowDto;
    }

    private int getFlowType(Forward forward) {
        int defaultFlowType = 2;
        if (forward == null) return defaultFlowType;
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return defaultFlowType;
        return tunnel.getFlow();
    }

    private void updateForwardFlow(String forwardId, FlowDto flowStats) {
        // 对相同转发的流量更新进行同步，避免并发覆盖
        synchronized (getForwardLock(forwardId)) {
            UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", forwardId);
            // 必须是「一条 UPDATE 内自增」的原子写法，不能改成"先查再改"（并发会丢流量）。
            // MyBatis-Plus 3.4.1 的 Update 接口只有 set(R,Object) / setSql(String) /
            // setSql(boolean,String) 三个重载，【没有可变参数版本】：
            //   - setSql("in_flow = in_flow + {0}", d) 编译期即报错；
            //   - UpdateWrapper.set(...) 生成的是 "column=value" 形式，无法表达 "column=column+?"；
            //   - 若退化成 setSql("in_flow = in_flow + " + d) 则是字符串拼接，非参数绑定。
            // 因此这里手工登记一个包装器参数，再用 setSql 引用它，得到
            //   in_flow = in_flow + #{ew.paramNameValuePairs.MPGENVALx}
            // 既保持原子自增语义，又是真正的预编译占位符（无 SQL 注入面）。
            setAtomicIncrement(updateWrapper, "in_flow", flowStats.getD());
            setAtomicIncrement(updateWrapper, "out_flow", flowStats.getU());

            forwardService.update(null, updateWrapper);
        }
    }

    private void updateUserFlow(String userId, FlowDto flowStats) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);

            setAtomicIncrement(updateWrapper, "in_flow", flowStats.getD());
            setAtomicIncrement(updateWrapper, "out_flow", flowStats.getU());

            userService.update(null, updateWrapper);
        }
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新，返回成功
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            setAtomicIncrement(updateWrapper, "in_flow", flowStats.getD());
            setAtomicIncrement(updateWrapper, "out_flow", flowStats.getU());
            userTunnelService.update(null, updateWrapper);
        }
    }

    /**
     * 生成「column = column + ?」的原子自增 SET 片段。
     * <p>
     * 手工把增量登记进 {@code paramNameValuePairs}，再用 {@code #{ew.paramNameValuePairs.xxx}}
     * 引用它。占位符名用列名 + 序号，避免一次 wrapper 内多次调用互相覆盖。
     * 注意不要改成字符串拼接（"in_flow = in_flow + " + d），那样没有参数绑定。
     */
    private void setAtomicIncrement(UpdateWrapper<?> updateWrapper, String column, Long delta) {
        String paramName = column + "_DELTA_" + System.nanoTime();
        updateWrapper.getParamNameValuePairs().put(paramName, delta == null ? 0L : delta);
        updateWrapper.setSql(true,
                column + " = " + column + " + #{ew.paramNameValuePairs." + paramName + "}");
    }

    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    private Object getForwardLock(String forwardId) {
        return FORWARD_LOCKS.computeIfAbsent(forwardId, k -> new Object());
    }

    private boolean isValidNode(String secret) {
        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", secret));
        return nodeCount > 0;
    }

    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}

package com.admin.common.utils;

import com.admin.common.dto.DiagnosisResult;
import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 端口/隧道诊断的 TCP 探测执行器。
 *
 * <p>历史实现（ForwardServiceImpl / TunnelServiceImpl 各有一份拷贝）在节点只回了 “OK” 但没带
 * 探测数据、或数据结构解析失败时，会直接判定为“TCP连接成功”，导致面板显示一切正常而实际端口不通。
 * 这里统一为：只有节点明确返回 success=true 才算连通，其余情况一律如实报告失败原因。</p>
 */
@Slf4j
public final class TcpPingDiagnosisRunner {

    public static final int DEFAULT_COUNT = 4;
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private TcpPingDiagnosisRunner() {
    }

    public static DiagnosisResult run(Node node, String targetIp, int port, String description) {
        return run(node, targetIp, port, description, WebSocketServer::send_msg);
    }

    public static DiagnosisResult run(Node node, String targetIp, int port, String description, NodeCommandSender sender) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node == null ? null : node.getId());
        result.setNodeName(node == null ? null : node.getName());
        result.setTargetIp(targetIp);
        result.setTargetPort(port);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        if (node == null || node.getId() == null) {
            return fail(result, "节点不存在");
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("ip", targetIp);
            payload.put("port", port);
            payload.put("count", DEFAULT_COUNT);
            payload.put("timeout", DEFAULT_TIMEOUT_MS);

            GostDto gostResult = sender.send(node.getId(), payload, "TcpPing");
            if (gostResult == null) {
                return fail(result, "节点无响应");
            }
            if (!"OK".equals(gostResult.getMsg())) {
                return fail(result, gostResult.getMsg() == null || gostResult.getMsg().isEmpty()
                        ? "节点无响应" : gostResult.getMsg());
            }

            JSONObject response = toJsonObject(gostResult.getData());
            if (response == null) {
                // 关键：不能把“没有探测数据”当成连通
                return fail(result, "节点未返回探测数据，无法确认端口是否连通");
            }

            if (!response.getBooleanValue("success")) {
                String errorMessage = response.getString("errorMessage");
                return fail(result, errorMessage == null || errorMessage.isEmpty() ? "TCP连接失败" : errorMessage);
            }

            result.setSuccess(true);
            result.setMessage("TCP连接成功");
            result.setAverageTime(response.getDoubleValue("averageTime"));
            result.setPacketLoss(response.getDoubleValue("packetLoss"));
            return result;
        } catch (Exception e) {
            log.warn("节点 {} 诊断 {}:{} 执行异常: {}", node.getId(), targetIp, port, e.getMessage());
            return fail(result, "诊断执行异常: " + e.getMessage());
        }
    }

    private static JSONObject toJsonObject(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        try {
            return JSONObject.parseObject(JSONObject.toJSONString(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static DiagnosisResult fail(DiagnosisResult result, String message) {
        result.setSuccess(false);
        result.setMessage(message);
        result.setAverageTime(-1.0);
        result.setPacketLoss(100.0);
        return result;
    }
}

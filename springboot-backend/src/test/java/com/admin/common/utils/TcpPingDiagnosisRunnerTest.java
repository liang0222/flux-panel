package com.admin.common.utils;

import com.admin.common.dto.DiagnosisResult;
import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端口/隧道诊断的 TCP 探测结果解析测试。
 *
 * <p>回归重点：节点只回 “OK” 但没有探测数据、或数据结构异常时，不能再判定为“连接成功”，
 * 否则面板会显示“全部正常”而实际端口不通。</p>
 */
class TcpPingDiagnosisRunnerTest {

    private Node node() {
        Node node = new Node();
        node.setId(7L);
        node.setName("entry-hk");
        return node;
    }

    private GostDto okWithData(Object data) {
        GostDto dto = new GostDto();
        dto.setMsg("OK");
        dto.setData(data);
        return dto;
    }

    @Test
    @DisplayName("节点返回 success=true 时按成功处理并带上延迟与丢包率")
    void successResultIsParsed() {
        JSONObject data = new JSONObject();
        data.put("success", true);
        data.put("averageTime", 261.0);
        data.put("packetLoss", 0.0);

        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "111.119.202.249", 1000, "入口->出口",
                (nodeId, payload, type) -> okWithData(data));

        assertTrue(result.isSuccess());
        assertEquals("TCP连接成功", result.getMessage());
        assertEquals(261.0, result.getAverageTime(), 0.001);
        assertEquals(0.0, result.getPacketLoss(), 0.001);
        assertEquals("111.119.202.249", result.getTargetIp());
        assertEquals(1000, result.getTargetPort());
    }

    @Test
    @DisplayName("节点返回 success=false 时如实报告错误原因")
    void failedResultReportsErrorMessage() {
        JSONObject data = new JSONObject();
        data.put("success", false);
        data.put("errorMessage", "dial tcp [2400:54a0:20c0:300:7::a]:58781: connect: connection refused");

        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "2400:54a0:20c0:300:7::a", 58781, "入口->出口",
                (nodeId, payload, type) -> okWithData(data));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("connection refused"));
        assertEquals(100.0, result.getPacketLoss(), 0.001);
        assertEquals(-1.0, result.getAverageTime(), 0.001);
    }

    @Test
    @DisplayName("节点只回 OK 但没有探测数据时不能算成功")
    void missingDataIsNotTreatedAsSuccess() {
        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "10.0.0.1", 80, "入口->目标",
                (nodeId, payload, type) -> okWithData(null));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("未返回探测数据"));
        assertEquals(100.0, result.getPacketLoss(), 0.001);
    }

    @Test
    @DisplayName("探测数据无法解析时不能算成功")
    void unparsableDataIsNotTreatedAsSuccess() {
        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "10.0.0.1", 80, "入口->目标",
                (nodeId, payload, type) -> okWithData("not-a-json-object"));

        assertFalse(result.isSuccess());
        assertEquals(100.0, result.getPacketLoss(), 0.001);
    }

    @Test
    @DisplayName("节点不在线时如实报告节点返回的消息")
    void offlineNodeReportsMessage() {
        GostDto dto = new GostDto();
        dto.setMsg("节点不在线");

        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "10.0.0.1", 80, "入口->目标",
                (nodeId, payload, type) -> dto);

        assertFalse(result.isSuccess());
        assertEquals("节点不在线", result.getMessage());
    }

    @Test
    @DisplayName("发送命令抛异常时报告诊断执行异常")
    void senderExceptionIsReported() {
        DiagnosisResult result = TcpPingDiagnosisRunner.run(node(), "10.0.0.1", 80, "入口->目标",
                (nodeId, payload, type) -> {
                    throw new IllegalStateException("boom");
                });

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("诊断执行异常"));
    }

    @Test
    @DisplayName("节点为空时直接失败，不发起探测")
    void nullNodeFails() {
        DiagnosisResult result = TcpPingDiagnosisRunner.run(null, "10.0.0.1", 80, "入口->目标",
                (nodeId, payload, type) -> {
                    throw new AssertionError("不应发起探测");
                });

        assertFalse(result.isSuccess());
        assertEquals("节点不存在", result.getMessage());
    }
}

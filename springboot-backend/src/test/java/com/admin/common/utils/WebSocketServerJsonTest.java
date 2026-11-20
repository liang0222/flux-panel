package com.admin.common.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.admin.common.dto.GostDto;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketServerJsonTest {
    private static final long NODE_ID = 1910047L;
    private static final String SECRET = "opaque-ws-fixture-secret";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesNodeCommandExchangeWithoutLoggingPayloads(boolean encrypted) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(WebSocketServer.class);
        Level previousLevel = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logs);

        var server = new WebSocketServer();
        var node = new Node();
        node.setId(NODE_ID);
        server.nodeService = mock(NodeService.class);
        when(server.nodeService.getById(NODE_ID)).thenReturn(node);
        when(server.nodeService.updateById(node)).thenReturn(true);

        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(
                "id", NODE_ID, "type", "1", "nodeSecret", encrypted ? SECRET : ""));
        when(session.getId()).thenReturn("ws-json-fixture-session");
        when(session.isOpen()).thenReturn(true);
        AESCrypto crypto = new AESCrypto(SECRET);
        doAnswer(invocation -> {
            TextMessage outbound = invocation.getArgument(0);
            JSONObject packet = JSON.parseObject(outbound.getPayload());
            if (encrypted) {
                assertTrue(packet.getBooleanValue("encrypted"));
                assertTrue(packet.getLongValue("timestamp") > 0);
                packet = JSON.parseObject(crypto.decryptString(packet.getString("data")));
            }
            assertEquals("Ping", packet.getString("type"));
            assertEquals("opaque-ws-fixture-command", packet.getJSONObject("data").getString("name"));
            String response = JSON.toJSONString(Map.of(
                    "type", "PingResponse", "requestId", packet.getString("requestId"),
                    "message", "opaque-ws-fixture-message", "data", Map.of(
                            "success", true, "averageTime", 12.5, "detail", "opaque-ws-fixture-data")));
            if (encrypted) {
                response = JSON.toJSONString(Map.of(
                        "encrypted", true, "data", crypto.encrypt(response), "timestamp", System.currentTimeMillis()));
            }
            server.handleTextMessage(session, new TextMessage(response));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        try {
            server.afterConnectionEstablished(session);
            GostDto result = WebSocketServer.send_msg(NODE_ID, Map.of("name", "opaque-ws-fixture-command"), "Ping");

            assertEquals("opaque-ws-fixture-message", result.getMsg());
            JSONObject data = assertInstanceOf(JSONObject.class, result.getData());
            assertTrue(data.getBooleanValue("success"));
            assertEquals(12.5, data.getDoubleValue("averageTime"));
            assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("收到响应")));
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("opaque-ws-fixture-"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            server.afterConnectionClosed(session, CloseStatus.NORMAL);
            logger.detachAppender(logs);
            logger.setLevel(previousLevel);
            logs.stop();
        }
    }
}

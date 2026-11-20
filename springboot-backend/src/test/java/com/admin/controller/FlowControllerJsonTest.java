package com.admin.controller;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowControllerJsonTest {
    private static final String SECRET = "fixture-only-node-secret";
    private final FlowController controller = new FlowController();

    @BeforeEach
    void setUp() {
        var node = new Node();
        node.setId(71L);
        controller.nodeService = mock(NodeService.class);
        controller.checkGostConfigAsync = mock(CheckGostConfigAsync.class);
        when(controller.nodeService.getOne(any())).thenReturn(node);
        when(controller.nodeService.existsBySecret(SECRET)).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsNodeConfigJson(boolean encrypted) {
        String config = """
                {"services":[{"name":"fixture-service","addr":":18080"}],
                 "chains":[{"name":"fixture-chain"}],"limiters":[{"name":"fixture-limiter"}]}
                """;
        assertEquals("ok", controller.config(encode(config, encrypted), SECRET));

        var argument = ArgumentCaptor.forClass(GostConfigDto.class);
        verify(controller.checkGostConfigAsync).cleanNodeConfigs(eq("71"), argument.capture());
        GostConfigDto parsed = argument.getValue();
        assertEquals("fixture-service", parsed.getServices().getFirst().getName());
        assertEquals("fixture-chain", parsed.getChains().getFirst().getName());
        assertEquals("fixture-limiter", parsed.getLimiters().getFirst().getName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsNodeUploadJson(boolean encrypted) {
        String flow = "[{\"n\":\"web_api\",\"u\":17,\"d\":19}]";
        assertEquals("ok", controller.uploadFlowData(encode(flow, encrypted), SECRET));
    }

    private static String encode(String payload, boolean encrypted) {
        return encrypted ? JSON.toJSONString(Map.of(
                "encrypted", true, "data", new AESCrypto(SECRET).encrypt(payload), "timestamp", 1_700_000_000_000L))
                : payload;
    }
}

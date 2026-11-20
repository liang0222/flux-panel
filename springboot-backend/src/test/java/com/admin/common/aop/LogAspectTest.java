package com.admin.common.aop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.lang.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogAspectTest {
    private final Logger aspectLogger = (Logger) LoggerFactory.getLogger(LogAspect.class);
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private FixtureController controller;

    @BeforeEach
    void setUp() {
        events.start();
        aspectLogger.addAppender(events);
        handlerLogger.addAppender(events);
        AspectJProxyFactory factory = new AspectJProxyFactory(new FixtureController());
        factory.addAspect(new LogAspect());
        controller = factory.getProxy();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        aspectLogger.detachAppender(events);
        handlerLogger.detachAppender(events);
        events.stop();
    }

    @Test
    void logsOnlyMetadataForNestedPayloadAndResponse() {
        bindRequest("192.0.2.5");
        var payload = new Payload("opaque-record-fixture", new Object[]{
                Map.of("customAuth", List.of(820573499911234L, "opaque-map-fixture")),
                new String[]{"opaque-array-fixture"}
        });
        var response = R.ok(Map.of("token", "opaque-response-fixture"));
        response.setMsg("opaque-response-message-fixture");

        assertSame(response, controller.echo(payload, "opaque-raw-json-fixture", response));
        assertEquals(1, events.list.size());
        String logged = events.list.getFirst().getFormattedMessage();
        assertTrue(logged.contains("请求方式:[POST]"));
        assertTrue(logged.contains("IP地址:[192.0.2.5]"));
        assertTrue(logged.contains("FixtureController.echo"));
        assertTrue(logged.contains("业务码:[0]"));
        assertTrue(logged.contains("用户ID:[未登录]"));
        assertFalse(logged.contains("opaque-"));
        assertFalse(logged.contains("820573499911234"));
    }

    @Test
    void stringReturnAndUntrustedRequestMetadataAreNotLogged() {
        bindRequest("opaque-proxy-header-fixture");

        assertEquals("opaque-string-result-fixture", controller.text("opaque-string-result-fixture"));
        assertEquals(1, events.list.size());
        String logged = events.list.getFirst().getFormattedMessage();
        assertTrue(logged.contains("IP地址:[-]"));
        assertTrue(logged.contains("业务码:[-]"));
        assertFalse(logged.contains("opaque-"));
    }

    @Test
    void logsExceptionTypeWithoutItsMessagesOrThrowable() {
        bindRequest("2001:db8::5");
        var failure = new IllegalStateException("opaque-exception-fixture",
                new IllegalArgumentException("opaque-cause-fixture"));

        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.fail(failure)));
        R response = new GlobalExceptionHandler().Exception(failure);
        assertEquals(-2, response.getCode());
        assertEquals(failure.getMessage(), response.getMsg());
        assertEquals(2, events.list.size());
        assertTrue(events.list.getFirst().getFormattedMessage().contains("IP地址:[2001:db8::5]"));
        for (ILoggingEvent event : events.list) {
            assertTrue(event.getFormattedMessage().contains(IllegalStateException.class.getName()));
            assertFalse(event.getFormattedMessage().contains("opaque-"));
            assertNull(event.getThrowableProxy());
        }
    }

    @Test
    void missingServletContextDoesNotChangeMethodResult() {
        assertNull(RequestContextHolder.getRequestAttributes());
        assertNotNull(controller.text("ordinary internal call"));
        assertTrue(events.list.isEmpty());
    }

    private void bindRequest(String forwardedAddress) {
        var request = new MockHttpServletRequest("POST", "/opaque-path-fixture");
        request.setQueryString("secret=opaque-query-fixture");
        request.addHeader("Authorization", "opaque-auth-fixture");
        request.addHeader("Cookie", "session=opaque-cookie-fixture");
        request.addHeader("X-Forwarded-For", forwardedAddress);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public record Payload(String alternateField, Object[] nested) {}

    static class FixtureController {
        @LogAnnotation
        public R echo(Payload argument, String rawData, R result) {
            return result;
        }

        @LogAnnotation
        public String text(String value) {
            return value;
        }

        @LogAnnotation
        public void fail(RuntimeException failure) {
            throw failure;
        }
    }
}

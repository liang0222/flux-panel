package com.admin.config;

import com.admin.common.task.CheckGostConfigAsync;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认 {@link CheckGostConfigAsync} 确实被 Spring 代理。
 * <p>
 * {@code @Async} 依赖代理生效：若该类被 {@code new} 出来、或方法被同类内部调用、
 * 或类是 final 的，注解都会静默失效，任务退化成在调用线程同步执行——
 * 表现为「配了线程池却一个线程都没有」。
 */
@SpringBootTest
class AsyncProxyCheckTest {

    @Autowired
    private CheckGostConfigAsync bean;

    @Test
    void asyncBeanIsProxied() {
        assertTrue(AopUtils.isAopProxy(bean),
                "CheckGostConfigAsync 必须是代理对象，否则 @Async 不生效");
    }
}

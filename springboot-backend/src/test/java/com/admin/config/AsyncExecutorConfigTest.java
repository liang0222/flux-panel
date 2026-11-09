package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定 {@code spring.task.execution} 的绑定结果。
 * <p>
 * 背景：本工程唯一的 {@code @Async} 是 {@code CheckGostConfigAsync.cleanNodeConfigs}，
 * 不显式配置时用 Spring Boot 默认的 {@code applicationTaskExecutor}——
 * core=8、max=Integer.MAX_VALUE、queue=Integer.MAX_VALUE，队列无界。
 * 这里断言 YAML 里的上界确实被绑到了执行器上，避免「改了配置但没生效」。
 */
@SpringBootTest
class AsyncExecutorConfigTest {

    @Autowired
    private ThreadPoolTaskExecutor applicationTaskExecutor;

    @Test
    void asyncPoolHasExplicitBounds() {
        assertEquals(2, applicationTaskExecutor.getCorePoolSize());
        assertEquals(4, applicationTaskExecutor.getMaxPoolSize());
        assertEquals(100, applicationTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()
                + applicationTaskExecutor.getThreadPoolExecutor().getQueue().size());
        assertEquals("flux-async-", applicationTaskExecutor.getThreadNamePrefix());
    }
}

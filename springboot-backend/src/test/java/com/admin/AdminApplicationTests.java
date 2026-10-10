package com.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 应用可加载性冒烟测试。
 *
 * <p>这里刻意<b>不</b>使用 {@code @SpringBootTest}：该注解会启动完整 Spring 上下文，
 * 并依据 application.yml 去连接外部 MySQL（数据源为 ${DB_HOST}/${DB_NAME}）。
 * 在没有 MySQL 的 CI 或本地环境下，整个 mvn test 都会因此失败，
 * 而它实际并未验证任何业务逻辑（原实现是一个空测试方法）。
 *
 * <p>需要真正的集成测试时，请另建带测试专用数据源（如 Testcontainers 或 H2）的测试类，
 * 并注意 H2 下 user 是保留字。
 */
class AdminApplicationTests {

    @Test
    void applicationClassIsPresent() {
        // 纯类加载检查：不启动容器、不连接数据库
        assertNotNull(AdminApplication.class);
    }
}

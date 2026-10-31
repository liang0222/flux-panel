package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * SQLite 数据库配置
 * 启用 WAL (Write-Ahead Logging) 模式以提高并发性能
 * 添加定期 checkpoint 和优雅关闭处理
 */
@Slf4j
@Component
@EnableScheduling
public class SQLiteConfig implements ApplicationRunner {

    private final DataSource dataSource;

    public SQLiteConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA cache_size=-64000;"); // 64MB 缓存
            statement.execute("PRAGMA temp_store=MEMORY;");
            statement.execute("PRAGMA busy_timeout=5000;"); // 5秒超时
            statement.execute("PRAGMA wal_autocheckpoint=1000;"); // 每1000页自动checkpoint
            
            log.info("SQLite WAL mode configured successfully");
        } catch (Exception e) {
            log.error("Failed to configure SQLite database", e);
            throw e;
        }
    }
    
    /**
     * 定期执行 checkpoint，确保 WAL 文件内容写入主数据库
     * 每5分钟执行一次
     *
     * 使用 PASSIVE 而不是 TRUNCATE：两者语义差别只在「是否尝试收缩 WAL 文件」，
     * 都把已提交页回写主库，因此数据安全性完全一致。但 TRUNCATE 要求拿到写锁并
     * 等待所有读快照释放，拿不到时会按 busy_timeout 反复重试、期间占据写锁位置。
     *
     * 实测（sqlite-jdbc 3.45.0.0，持有活跃读快照 + WAL 667 KB 积压）：
     *   TRUNCATE  busy=1 done=0 耗时 5009ms，同时并发的写方被卡 4835ms
     *   RESTART   只比 TRUNCATE 少了截断，同样卡 5022ms
     *   FULL      同样卡 5013ms
     *   PASSIVE   busy=0 耗时 1ms，写方 0ms 不受影响
     * 生产上该 checkpoint 与流量上报共用同一个库，TRUNCATE 会把上报的写操作
     * 拖到 busy_timeout 边界，进而撞上 agent 侧 5 秒的 HTTP 超时。
     * WAL 文件不会因此无限增长：wal_autocheckpoint=1000 页（4MB）仍会自动回写，
     * 关闭时的 TRUNCATE 也保留。
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void performCheckpoint() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            statement.execute("PRAGMA wal_checkpoint(PASSIVE);");
            log.debug("SQLite WAL checkpoint completed");
        } catch (Exception e) {
            log.error("Failed to perform SQLite checkpoint", e);
        }
    }
    
    /**
     * 应用关闭前执行最终的 checkpoint，确保所有数据都写入主数据库文件
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Performing final SQLite checkpoint before shutdown...");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            // 强制执行 checkpoint，将所有 WAL 内容写入主数据库
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            log.info("Final SQLite checkpoint completed successfully");
        } catch (Exception e) {
            log.error("Failed to perform final SQLite checkpoint", e);
        }
    }
}


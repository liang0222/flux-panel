package com.admin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库连接串的护栏测试。
 * <p>
 * 背景（真实事故预演）：sqlite-jdbc 只识别一组固定的 URL 参数名。一旦 URL 里出现它不认识的
 * 参数（例如 {@code wal_autocheckpoint}、{@code auto_vacuum}、任意拼写错误），驱动不会报错，
 * 而是**停止解析并把剩余的 {@code ?xxx} 整段拼进文件名**，于是应用打开的是一个全新空库：
 * <ul>
 *   <li>面板上所有转发、用户、隧道、统计全部「消失」；</li>
 *   <li>原库文件仍然完好躺在磁盘上，但应用读的是另一个文件；</li>
 *   <li>不抛任何启动异常，日志里也看不出异常，属于最难发现的静默数据事故。</li>
 * </ul>
 * 实测（sqlite-jdbc 3.45.0.0）：{@code ?wal_autocheckpoint=1000} 会生成名为
 * {@code gost.db?wal_autocheckpoint=1000} 的空文件，随后任何查询都报
 * {@code [SQLITE_ERROR] ... (no such table: forward)}。
 * <p>
 * 本测试把「哪些参数是安全的」固定下来，任何人往 URL 里加未知参数都会在这里失败，
 * 而不是等到生产上数据「消失」才发现。
 */
class SqliteDatasourceUrlTest {

    /**
     * sqlite-jdbc 3.45.0.0 实测被识别、且本项目确实需要的参数名白名单。
     * 新增参数前必须先用真实库验证驱动能识别，再把名字加进这里。
     */
    private static final Set<String> ALLOWED_URL_PARAMS = new HashSet<>(Arrays.asList(
            "journal_mode",
            "synchronous",
            "busy_timeout",
            "cache_size",
            "temp_store"
    ));

    @SuppressWarnings("unchecked")
    private String loadConfiguredUrl() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertTrue(resource.exists(), "application.yml 必须存在于 classpath");
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            assertNotNull(spring, "application.yml 缺少 spring 节点");
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            assertNotNull(datasource, "application.yml 缺少 spring.datasource 节点");
            String url = (String) datasource.get("url");
            assertNotNull(url, "application.yml 缺少 spring.datasource.url");
            return url;
        }
    }

    /** 把 URL 里的查询串拆成参数名列表（保持出现顺序，便于定位是哪一个出错）。 */
    private List<String> extractParamNames(String url) {
        List<String> names = new ArrayList<>();
        int q = url.indexOf('?');
        if (q < 0) {
            return names;
        }
        String query = url.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            names.add(eq < 0 ? pair : pair.substring(0, eq));
        }
        return names;
    }

    @Test
    @DisplayName("application.yml 的连接串只能包含驱动已验证识别的参数名")
    void urlContainsOnlyKnownParams() throws Exception {
        String url = loadConfiguredUrl();
        List<String> names = extractParamNames(url);

        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            if (!ALLOWED_URL_PARAMS.contains(name)) {
                unknown.add(name);
            }
        }

        assertTrue(unknown.isEmpty(),
                "连接串里出现了 sqlite-jdbc 不识别的参数：" + unknown
                        + "。驱动遇到未知参数会停止解析并把剩余内容当作文件名，"
                        + "导致打开一个全新的空库（面板数据全部「消失」）。"
                        + "允许的参数只有：" + ALLOWED_URL_PARAMS
                        + "；当前 URL = " + url);
    }

    @Test
    @DisplayName("连接串必须显式设置 WAL 与 NORMAL，不能退回驱动默认的 delete/FULL")
    void urlPinsWalAndNormalSynchronous() throws Exception {
        String url = loadConfiguredUrl();
        assertTrue(url.contains("journal_mode=WAL"),
                "连接串必须设置 journal_mode=WAL，否则每个连接都是 delete 模式，"
                        + "读写互相阻塞。当前 URL = " + url);
        assertTrue(url.contains("synchronous=NORMAL"),
                "连接串必须设置 synchronous=NORMAL。驱动默认 FULL 会让每条 UPDATE 都做一次"
                        + "完整 fsync，实测 109 条并发写从 31ms 劣化到 260ms。当前 URL = " + url);
        assertTrue(url.contains("busy_timeout=5000"),
                "连接串必须设置 busy_timeout，否则等待写锁的线程会立刻失败。当前 URL = " + url);
    }

    @Test
    @DisplayName("未知参数会真的打开一个空库——用真实文件复现该行为，防止有人「优化」掉上面的断言")
    void unknownParamReallyOpensEmptyDatabase() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("flux-sqlite-url");
        java.nio.file.Path db = dir.resolve("guard.db");

        // 先建一个正常的库，表结构里有一个标记表
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE forward (id INTEGER PRIMARY KEY, in_flow INTEGER)");
            s.execute("INSERT INTO forward (id, in_flow) VALUES (1, 100)");
        }

        // 1) 安全参数：能读到原表
        String safeUrl = "jdbc:sqlite:" + db
                + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&cache_size=-8000&temp_store=MEMORY";
        try (Connection c = DriverManager.getConnection(safeUrl);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM forward")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "带安全参数的连接必须能读到原库的表");
        }

        // 2) 未知参数：驱动把 "?foo=1" 拼进文件名，打开的是另一个空库 → 表不存在
        String brokenUrl = "jdbc:sqlite:" + db + "?journal_mode=WAL&foo=1";
        boolean sawMissingTable = false;
        try (Connection c = DriverManager.getConnection(brokenUrl);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM forward")) {
            rs.next();
        } catch (java.sql.SQLException e) {
            sawMissingTable = e.getMessage() != null && e.getMessage().contains("no such table");
        }
        assertTrue(sawMissingTable,
                "预期未知参数会让驱动打开空库并报 no such table；若这里不再成立，"
                        + "说明驱动行为变了，需要重新评估整个参数白名单");

        // 3) 确认确实多出了一个文件名里带 "?foo=1" 的文件，这就是「数据消失」的物证
        Set<String> names = new LinkedHashSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        boolean weirdFileExists = names.stream().anyMatch(n -> n.contains("?foo=1"));
        assertTrue(weirdFileExists,
                "预期目录里出现文件名含 '?foo=1' 的怪文件，实际文件列表 = " + names);

        // 4) 原库本身没有被破坏
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM forward")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "原库必须完好无损——事故只是应用读错了文件");
        }

        assertFalse(names.isEmpty(), "临时目录不应为空");
    }

    @Test
    @DisplayName("定期 checkpoint 必须用 PASSIVE，不能用 TRUNCATE/RESTART/FULL")
    void periodicCheckpointMustNotUseBlockingMode() throws Exception {
        // 只检查 performCheckpoint 这一个方法体：关闭时的 TRUNCATE 是有意保留的
        // （应用已经在退出，不存在「拖慢在线请求」的问题），不能一并禁掉。
        java.nio.file.Path source = java.nio.file.Paths.get(
                "src/main/java/com/admin/config/SQLiteConfig.java");
        assertTrue(java.nio.file.Files.exists(source),
                "找不到 SQLiteConfig.java，测试需在 springboot-backend 模块目录下运行，实际路径 = "
                        + source.toAbsolutePath());

        String text = new String(java.nio.file.Files.readAllBytes(source), StandardCharsets.UTF_8);
        int methodAt = text.indexOf("public void performCheckpoint()");
        assertTrue(methodAt > 0, "SQLiteConfig.java 里找不到 performCheckpoint 方法");
        int bodyEnd = text.indexOf("\n    }", methodAt);
        assertTrue(bodyEnd > methodAt, "无法定位 performCheckpoint 方法体结束位置");
        String body = text.substring(methodAt, bodyEnd);

        for (String blocking : new String[]{"wal_checkpoint(TRUNCATE)", "wal_checkpoint(RESTART)", "wal_checkpoint(FULL)"}) {
            assertFalse(body.contains(blocking),
                    "performCheckpoint 使用了 " + blocking + "。实测（sqlite-jdbc 3.45.0.0，"
                            + "存在活跃读快照 + WAL 积压）：TRUNCATE busy=1 done=0 耗时 5009ms、"
                            + "并发写方被卡 4835ms；RESTART 5022ms；FULL 5013ms；"
                            + "只有 PASSIVE 是 1ms 返回且写方 0ms。定期 checkpoint 必须用 PASSIVE。");
        }
        assertTrue(body.contains("wal_checkpoint(PASSIVE)"),
                "performCheckpoint 应使用 wal_checkpoint(PASSIVE)");
    }
}

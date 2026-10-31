# TRUNCATE checkpoint 与上报撞车 —— 生产卷实测

环境：生产服务器 87.83.207.7，真实卷目录 /var/lib/docker/volumes/sqlite_data/_data
数据库：生产库一致性副本（.backup 导出），forward 109 行
连接串：file:<db>?busy_timeout=5000（与生产 application.yml 完全一致，synchronous=FULL）

## 场景 A：生产现状 checkpoint(TRUNCATE)

    WAL 积压 2.6 MB（2776912 字节，与生产峰值同量级）
    [checkpoint TRUNCATE] 10022ms  busy=1 log=674 done=674
    上报（checkpoint 期间，synchronous=2）：2 次  p50=5019ms max=5022ms  失败=1/2
    首次失败：5022ms  OperationalError: database is locked
    上报（checkpoint 结束后）：3 次  max=83ms  失败=0/3

## 场景 B：修复后 checkpoint(PASSIVE)

    WAL 积压 2.6 MB
    [checkpoint PASSIVE] 12ms  busy=0 log=674 done=674
    上报（checkpoint 期间）：没能在窗口内发起写入（checkpoint 12ms 就结束了）
    上报（checkpoint 结束后）：3 次  max=68ms  失败=0/3

## 结论

1. TRUNCATE 在有活跃读快照 + WAL 积压时无法完成，会持写锁等满 busy_timeout，
   单次耗时 10 秒（内部两轮 5 秒）。
2. 这 10 秒内所有上报写入失败，失败耗时正好 5019~5022ms —— 与 agent 侧
   5 秒 HTTP 超时（Timeout: 5 * time.Second）完全吻合。
3. 错误文本 `database is locked` 与生产 `[SQLITE_BUSY] The database file is locked`
   同源。
4. 改 PASSIVE 后同一场景 12ms 完成，且 log=674 done=674 说明积压照常回写，
   不牺牲 checkpoint 效果。
5. 生产每 300 秒一次 checkpoint，只有恰好撞上长读快照时才卡住 —— 这解释了
   为什么 24 小时只有 58 次上报失败而不是每 5 分钟一次。

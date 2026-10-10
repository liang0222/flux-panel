-- ============================================================================
-- 端口流量排行榜 增量迁移脚本
-- ----------------------------------------------------------------------------
-- 适用场景：已经部署过 flux-panel，数据库里已有 forward / statistics_flow 等表，
--          只想新增「端口流量排行榜」所需的表结构，不重建整库。
--
-- 全新安装无需执行本脚本：gost.sql 已包含同样的表结构。
--
-- 执行方式（面板端 Docker 部署）：
--   docker exec -i gost-mysql mysql -u<DB_USER> -p<DB_PASSWORD> <DB_NAME> \
--     < migrate-port-ranking.sql
--
-- 例如默认值：
--   docker exec -i gost-mysql mysql -uroot -p你的密码 gost < migrate-port-ranking.sql
--
-- 本脚本可重复执行（使用 IF NOT EXISTS），不会破坏既有数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `statistics_flow_forward` (
  `id` int(10) NOT NULL AUTO_INCREMENT,
  `forward_id` int(10) NOT NULL COMMENT '转发ID（forward.id）',
  `user_id` int(10) NOT NULL COMMENT '转发所属用户ID',
  `name` varchar(255) DEFAULT NULL COMMENT '转发名称（冗余，转发删除后仍可展示）',
  `in_port` int(10) DEFAULT NULL COMMENT '入口端口',
  `out_port` int(10) DEFAULT NULL COMMENT '出口端口',
  `tunnel_id` int(10) DEFAULT NULL COMMENT '隧道ID',
  `flow` bigint(20) NOT NULL DEFAULT '0' COMMENT '本采样周期增量流量（字节）',
  `total_flow` bigint(20) NOT NULL DEFAULT '0' COMMENT '采样时刻累计流量（字节）',
  `time` varchar(100) NOT NULL COMMENT '采样小时，如 14:00',
  `created_time` bigint(20) NOT NULL COMMENT '采样时刻毫秒时间戳',
  PRIMARY KEY (`id`),
  KEY `idx_created_time` (`created_time`),
  KEY `idx_forward_id` (`forward_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='端口（转发）小时流量快照，用于端口流量排行榜';

-- ----------------------------------------------------------------------------
-- 校验：确认表已创建
-- ----------------------------------------------------------------------------
-- SHOW TABLES LIKE 'statistics_flow_forward';


-- ============================================================================
-- 以下为安全加固相关的增量变更（老库升级时必须执行）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. user 表新增三个字段（token 吊销与登录防爆破所需）
--
-- 说明：MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，
-- 这里用存储过程先查 information_schema 再决定是否添加，
-- 因此本脚本可以重复执行而不会报「字段已存在」。
-- ----------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `add_column_if_missing`;

DELIMITER $$
CREATE PROCEDURE `add_column_if_missing`(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @stmt = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', ddl);
        PREPARE s FROM @stmt;
        EXECUTE s;
        DEALLOCATE PREPARE s;
    END IF;
END$$
DELIMITER ;

-- MODIFY COLUMN 版本：仅当现有 COLUMN_TYPE 与目标不一致时才执行，
-- 避免每次跑迁移都重建整张表（user 表在 MySQL 5.7 上是重量级操作）。
DROP PROCEDURE IF EXISTS `modify_column_if_needed`;

DELIMITER $$
CREATE PROCEDURE `modify_column_if_needed`(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN new_type VARCHAR(128)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
          AND LOWER(COLUMN_TYPE) <> LOWER(new_type)
    ) THEN
        SET @stmt = CONCAT('ALTER TABLE `', tbl, '` MODIFY COLUMN `', col, '` ', new_type, ' NOT NULL');
        PREPARE s FROM @stmt;
        EXECUTE s;
        DEALLOCATE PREPARE s;
    END IF;
END$$
DELIMITER ;

CALL add_column_if_missing('user', 'token_version',
    '`token_version` int(10) NOT NULL DEFAULT 0 COMMENT ''token版本号，改密码时+1以吊销旧token''');
CALL add_column_if_missing('user', 'login_fail_count',
    '`login_fail_count` int(10) NOT NULL DEFAULT 0 COMMENT ''连续登录失败次数''');

CALL add_column_if_missing('user', 'locked_until',
    '`locked_until` bigint(20) NOT NULL DEFAULT 0 COMMENT ''账号锁定截止时间戳，0表示未锁定''');

-- tunnel.traffic_ratio：流量计费倍率，FlowController 依赖它做流量加权。
-- 老库若缺这一列，流量上报会直接抛 SQL 异常（表现为该节点流量统计静默停止）。
CALL add_column_if_missing('tunnel', 'traffic_ratio',
    '`traffic_ratio` decimal(10,1) NOT NULL DEFAULT ''1.0'' COMMENT ''流量计费倍率''');

-- 密码字段扩容：PBKDF2 哈希约 100 字符，原 varchar(100) 在极端情况下可能不够。
-- 这里同样要先查 information_schema：MODIFY COLUMN 虽然重复执行不报错，
-- 但每次都重建整张 user 表，属于无谓的锁表操作，且会让「可重复执行」的承诺失真。
CALL modify_column_if_needed('user', 'pwd', 'varchar(255)');

DROP PROCEDURE IF EXISTS `add_column_if_missing`;
DROP PROCEDURE IF EXISTS `modify_column_if_needed`;

-- ----------------------------------------------------------------------------
-- 2. 操作审计日志表
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` int(10) DEFAULT NULL COMMENT '操作者ID',
  `user_name` varchar(100) DEFAULT NULL COMMENT '操作者用户名',
  `ip` varchar(100) DEFAULT NULL COMMENT '来源IP',
  `method` varchar(20) DEFAULT NULL COMMENT 'HTTP方法',
  `action` varchar(255) DEFAULT NULL COMMENT '控制器.方法',
  `params` text COMMENT '请求参数（已脱敏）',
  `success` int(10) NOT NULL DEFAULT '1' COMMENT '1成功 0失败',
  `message` text COMMENT '结果摘要',
  `cost_ms` bigint(20) DEFAULT NULL COMMENT '耗时毫秒',
  `created_time` bigint(20) NOT NULL COMMENT '发生时间戳',
  PRIMARY KEY (`id`),
  KEY `idx_created_time` (`created_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- ----------------------------------------------------------------------------
-- 3. 升级说明（重要）
-- ----------------------------------------------------------------------------
-- 老库中的密码为无盐 MD5。新版本节点端会自动识别并允许登录，
-- 登录成功后【自动】把密码升级为 PBKDF2，用户无需重置密码。
-- 因此这里不需要、也不应该批量改写 user.pwd。
--
-- 校验：
--   SHOW COLUMNS FROM `user` LIKE 'token_version';
--   SHOW TABLES LIKE 'audit_log';


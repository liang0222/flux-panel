-- SQLite Auto-generated schema
-- This will be executed automatically on startup if tables don't exist

CREATE TABLE IF NOT EXISTS forward (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  user_name VARCHAR(100) NOT NULL,
  name VARCHAR(100) NOT NULL,
  tunnel_id INTEGER NOT NULL,
  remote_addr TEXT NOT NULL,
  strategy VARCHAR(100) NOT NULL DEFAULT 'fifo',
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  updated_time INTEGER NOT NULL,
  status INTEGER NOT NULL,
  inx INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS forward_port (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  forward_id INTEGER NOT NULL,
  node_id INTEGER NOT NULL,
  port INTEGER NOT NULL
);

-- 端口转发流量采集记录（每 5 分钟一条，按转发端口维度保存计数器快照与增量）
CREATE TABLE IF NOT EXISTS forward_flow_statistics (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  forward_id INTEGER NOT NULL,
  forward_name VARCHAR(100),
  port INTEGER,
  ports VARCHAR(200),
  node_id INTEGER,
  tunnel_id INTEGER,
  user_id INTEGER,
  user_name VARCHAR(100),
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  in_flow_delta INTEGER NOT NULL DEFAULT 0,
  out_flow_delta INTEGER NOT NULL DEFAULT 0,
  flow INTEGER NOT NULL DEFAULT 0,
  total_flow INTEGER NOT NULL DEFAULT 0,
  reset_flag INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  time VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_forward_flow_statistics_time ON forward_flow_statistics (created_time);
CREATE INDEX IF NOT EXISTS idx_forward_flow_statistics_forward ON forward_flow_statistics (forward_id, created_time);

CREATE TABLE IF NOT EXISTS node (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  secret VARCHAR(100) NOT NULL,
  server_ip VARCHAR(100) NOT NULL,
  port TEXT NOT NULL,
  interface_name VARCHAR(200),
  version VARCHAR(100),
  http INTEGER NOT NULL DEFAULT 0,
  tls INTEGER NOT NULL DEFAULT 0,
  socks INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL,
  tcp_listen_addr VARCHAR(100) NOT NULL DEFAULT '[::]',
  udp_listen_addr VARCHAR(100) NOT NULL DEFAULT '[::]'
);

-- 节点上报批次的去重凭据，与流量计数在同一事务提交。
CREATE TABLE IF NOT EXISTS flow_report_batch (
  node_id INTEGER NOT NULL,
  batch_id TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (node_id, batch_id)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS speed_limit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  speed INTEGER NOT NULL,
  tunnel_id INTEGER NOT NULL,
  tunnel_name VARCHAR(100) NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS statistics_flow (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  total_flow INTEGER NOT NULL,
  time VARCHAR(100) NOT NULL,
  created_time INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_statistics_flow_user_id ON statistics_flow (user_id, id);
CREATE INDEX IF NOT EXISTS idx_statistics_flow_user_created ON statistics_flow (user_id, created_time);
CREATE INDEX IF NOT EXISTS idx_statistics_flow_created ON statistics_flow (created_time);

CREATE TABLE IF NOT EXISTS tunnel (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(100) NOT NULL,
  traffic_ratio REAL NOT NULL DEFAULT 1.0,
  type INTEGER NOT NULL,
  protocol VARCHAR(10) NOT NULL DEFAULT 'tls',
  flow INTEGER NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER NOT NULL,
  status INTEGER NOT NULL,
  in_ip TEXT
);

CREATE TABLE IF NOT EXISTS chain_tunnel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tunnel_id INTEGER NOT NULL ,
    chain_type VARCHAR(10) NOT NULL,
    node_id INTEGER NOT NULL ,
    port INTEGER,
    strategy VARCHAR(10),
    inx  INTEGER,
    protocol  VARCHAR(10)
);


CREATE TABLE IF NOT EXISTS user (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user VARCHAR(100) NOT NULL,
  pwd VARCHAR(100) NOT NULL,
  role_id INTEGER NOT NULL,
  exp_time INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  flow_reset_time INTEGER NOT NULL,
  num INTEGER NOT NULL,
  created_time INTEGER NOT NULL,
  updated_time INTEGER,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS user_tunnel (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  tunnel_id INTEGER NOT NULL,
  speed_id INTEGER,
  num INTEGER NOT NULL,
  flow INTEGER NOT NULL,
  in_flow INTEGER NOT NULL DEFAULT 0,
  out_flow INTEGER NOT NULL DEFAULT 0,
  flow_reset_time INTEGER NOT NULL,
  exp_time INTEGER NOT NULL,
  status INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS vite_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name VARCHAR(200) NOT NULL UNIQUE,
  value VARCHAR(200) NOT NULL,
  time INTEGER NOT NULL
);


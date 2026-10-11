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


-- 端口（转发）级流量快照，供「端口流量排行榜」使用。
-- 说明：本表由 StatisticsFlowForwardAsync 每 5 分钟采集一次，
--       仅新增本表，不改动任何既有表结构（原版功能保持不变）。
CREATE TABLE IF NOT EXISTS statistics_flow_forward (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  forward_id INTEGER NOT NULL,
  user_id INTEGER,
  name VARCHAR(100),
  in_port INTEGER,
  remote_addr VARCHAR(200),
  tunnel_id INTEGER,
  flow INTEGER NOT NULL DEFAULT 0,
  total_flow INTEGER NOT NULL DEFAULT 0,
  created_time INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sff_created_time ON statistics_flow_forward (created_time);
CREATE INDEX IF NOT EXISTS idx_sff_forward_id ON statistics_flow_forward (forward_id);

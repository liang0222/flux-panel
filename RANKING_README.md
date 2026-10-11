# 端口流量排行榜（新增功能）

> 本功能基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 的 **beta（开发版）** 分支新增。
> **原版功能一行未改动**，本功能为纯增量。

## 功能说明

在面板上按「转发（端口）」维度统计流量，按流量降序排列，支持 4 个时间视图。

- **采集频率：每 5 分钟一次**
- **时间视图：今天 / 3 天 / 7 天 / 30 天**
- **数据保留：30 天**（超出自动清理）
- **删除联动**：删除转发、删除隧道时，自动清理该转发在排行榜中的历史记录

## 改动清单

### 新增文件（8 个）

| 文件 | 说明 |
|---|---|
| `entity/StatisticsFlowForward.java` | 快照实体 |
| `mapper/StatisticsFlowForwardMapper.java` | Mapper 接口 |
| `mapper/StatisticsFlowForwardMapper.xml` | 排行榜聚合查询 SQL |
| `service/StatisticsFlowForwardService.java` | Service 接口 |
| `service/impl/StatisticsFlowForwardServiceImpl.java` | Service 实现 |
| `common/task/StatisticsFlowForwardAsync.java` | 每 5 分钟采集任务 |
| `controller/RankingController.java` | 排行榜查询接口 |
| `RANKING_README.md` | 本文档 |

### 修改文件（2 个，均为纯追加）

| 文件 | 改动 |
|---|---|
| `resources/schema.sql` | **追加**一张表 `statistics_flow_forward` + 2 个索引 |
| `service/impl/ForwardServiceImpl.java` | **追加** 1 个注入字段、2 处清理调用、1 个私有方法；**未修改任何原有代码行** |

## 接口

### 查询端口流量排行榜

```
POST /api/v1/ranking/port
Authorization: <JWT token>
Content-Type: application/json

{
  "range": "today",     // today | 3d | 7d | 30d
  "limit": 100          // 可选，默认 100，最大 1000
}
```

响应：

```json
{
  "code": 0,
  "data": {
    "range": "today",
    "startTime": 1791648000000,
    "list": [
      {
        "forwardId": 2,
        "name": "转发名称",
        "inPort": 20002,
        "remoteAddr": "1.1.1.1:80",
        "tunnelId": 1,
        "userId": 1,
        "flow": 12000,
        "totalFlow": 12000
      }
    ]
  }
}
```

**权限**：管理员可看全部转发；普通用户只能看到自己的转发。

## 数据表

```sql
CREATE TABLE IF NOT EXISTS statistics_flow_forward (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  forward_id INTEGER NOT NULL,
  user_id INTEGER,
  name VARCHAR(100),
  in_port INTEGER,
  remote_addr VARCHAR(200),
  tunnel_id INTEGER,
  flow INTEGER NOT NULL DEFAULT 0,        -- 本周期增量流量
  total_flow INTEGER NOT NULL DEFAULT 0,  -- 采集时刻累计流量
  created_time INTEGER NOT NULL
);
```

## 实现要点

1. **增量计算**：`本周期增量 = 本次累计值 - 上次快照累计值`；若为负（用户重置流量、转发重建），按「本次全部计入」处理，与原版用户级统计口径一致。

2. **独立定时任务**：采集任务单独成类，不挂在原版 `StatisticsFlowAsync` 上。任一侧出错或调频都不会影响另一侧。

3. **冗余字段**：快照里冗余存了 `name` / `in_port` / `remote_addr`，即使转发已被删除，历史榜单也能正常展示（不会因为 JOIN 不到而变空白）。

4. **端口来源**：beta 版把 `in_port` 从 `forward` 表移到了 `forward_port` 表，采集时整表读一次做映射，不在循环里逐条查询。

## 验证结果（实测）

| 验证项 | 结果 |
|---|---|
| 新表自动创建 | ✅ 表 + 2 索引 |
| 排行榜查询 | ✅ 按流量降序（12000 → 5000 → 3000） |
| 时间范围筛选 | ✅ today/3d 不含 5 天前数据；7d/30d 正确包含 |
| 删除转发连带清理 | ✅ 该转发的榜单记录同步清除 |
| 删除隧道级联清理 | ✅ 转发、端口、榜单记录全部清零 |
| 原版 `StatisticsFlowAsync` | ✅ 一行未改动 |
| 其他原版 Java 文件 | ✅ 一行未改动 |
| 编译 | ✅ BUILD SUCCESS |

## 部署

```bash
# 面板端（docker compose，后端镜像需重新构建）
cd <仓库目录>
docker compose -f docker-compose-v4.yml up -d --build
```

数据库为 SQLite，新表会由 `schema.sql` 自动创建，**无需手动迁移**。

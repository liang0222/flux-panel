import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";

import { getForwardFlowRanking } from "@/api";
import type { ForwardFlowRange } from "@/api";
import { isAdmin } from "@/utils/auth";

interface FlowRankItem {
  rank: number;
  forwardId: number;
  forwardName: string;
  port?: number | null;
  ports?: number[];
  nodeId?: number | null;
  tunnelId?: number | null;
  tunnelName?: string;
  userId?: number | null;
  userName?: string;
  inFlow: number;
  outFlow: number;
  totalFlow: number;
  sampleCount: number;
  firstTime?: number | null;
  lastTime?: number | null;
  deleted?: boolean;
  status?: number | null;
}

interface FlowRanking {
  range: string;
  rangeLabel: string;
  days: number;
  startTime: number;
  endTime: number;
  startTimeText: string;
  endTimeText: string;
  sampleIntervalMinutes: number;
  retentionDays: number;
  trafficScope: string;
  trafficScopeLabel: string;
  items: FlowRankItem[];
  forwardCount: number;
  portCount: number;
  totalFlow: number;
  totalInFlow: number;
  totalOutFlow: number;
  lastCollectTime?: number | null;
  lastCollectTimeText?: string | null;
  generatedTime: number;
}

const RANGE_OPTIONS: { key: ForwardFlowRange; label: string }[] = [
  { key: "today", label: "今天" },
  { key: "3d", label: "近 3 天" },
  { key: "7d", label: "近 7 天" },
  { key: "30d", label: "近 30 天" },
];

// 与后端保持一致：以字节为单位展示流量
const formatFlow = (value: number | null | undefined): string => {
  const flow = value || 0;
  if (flow === 0) return "0 B";
  if (flow < 1024) return `${flow} B`;
  if (flow < 1024 * 1024) return `${(flow / 1024).toFixed(2)} KB`;
  if (flow < 1024 * 1024 * 1024) return `${(flow / (1024 * 1024)).toFixed(2)} MB`;
  return `${(flow / (1024 * 1024 * 1024)).toFixed(2)} GB`;
};

const formatPort = (item: FlowRankItem): string => {
  if (item.port === null || item.port === undefined) {
    return "--";
  }
  const extra = item.ports && item.ports.length > 1 ? item.ports.length - 1 : 0;
  return extra > 0 ? `${item.port} +${extra}` : `${item.port}`;
};

const getRankStyle = (rank: number): string => {
  if (rank === 1) return "bg-gradient-to-br from-amber-400 to-yellow-600 text-white shadow-sm";
  if (rank === 2) return "bg-gradient-to-br from-slate-300 to-slate-500 text-white shadow-sm";
  if (rank === 3) return "bg-gradient-to-br from-orange-400 to-amber-700 text-white shadow-sm";
  return "bg-default-100 text-default-600 dark:bg-default-200/20 dark:text-default-400";
};

export default function FlowRankingPage() {
  const navigate = useNavigate();
  const [range, setRange] = useState<ForwardFlowRange>("today");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [ranking, setRanking] = useState<FlowRanking | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  // 记录最近一次请求，快速切换统计范围时丢弃过期响应
  const requestIdRef = useRef(0);

  // 权限检查：排行榜涉及所有用户的端口，仅管理员可见
  useEffect(() => {
    if (!isAdmin()) {
      toast.error("权限不足，只有管理员可以访问此页面");
      navigate("/dashboard", { replace: true });
    }
  }, [navigate]);

  // mode: loading（切换范围/首次进入）、manual（点击刷新）、silent（自动刷新）
  const loadRanking = useCallback(async (mode: "loading" | "manual" | "silent" = "silent") => {
    const requestId = ++requestIdRef.current;
    if (mode === "loading") {
      setLoading(true);
    } else if (mode === "manual") {
      setRefreshing(true);
    }
    try {
      const res = await getForwardFlowRanking(range);
      if (requestId !== requestIdRef.current) {
        return;
      }
      if (res.code === 0 && res.data) {
        setRanking(res.data as FlowRanking);
        setLoadFailed(false);
      } else {
        setLoadFailed(true);
        toast.error(res.msg || "获取端口流量排行失败");
      }
    } catch (error) {
      if (requestId !== requestIdRef.current) {
        return;
      }
      console.error("获取端口流量排行失败:", error);
      setLoadFailed(true);
      toast.error("获取端口流量排行失败");
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [range]);

  // 切换统计范围后重新查询对应周期的排行榜
  useEffect(() => {
    loadRanking("loading");
    localStorage.setItem("e", "/flow-ranking");
  }, [loadRanking]);

  // 采集间隔为 5 分钟，页面每 60 秒静默刷新一次，离开页面后停止
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        loadRanking("silent");
      }
    }, 60 * 1000);

    return () => window.clearInterval(timer);
  }, [loadRanking]);

  const items = ranking?.items || [];
  const maxFlow = items.length > 0 ? Math.max(...items.map(item => item.totalFlow || 0)) : 0;
  const trafficScopeLabel = ranking?.trafficScopeLabel || "入站 + 出站";

  return (
    <div className="px-3 lg:px-6 py-8">
      {/* 页面头部 */}
      <div className="flex flex-col gap-4 mb-6 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground mb-1">端口流量排行</h1>
          <p className="text-sm text-default-600">
            按端口转发规则统计所选时间范围内转发的总流量（{trafficScopeLabel}），每
            {" "}{ranking?.sampleIntervalMinutes || 5} 分钟采集一次，历史数据保留
            {" "}{ranking?.retentionDays || 31} 天
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-default-400 hidden lg:inline">页面每 60 秒自动刷新</span>
          <Button
            size="sm"
            variant="flat"
            color="primary"
            isLoading={refreshing}
            onPress={() => loadRanking("manual")}
            startContent={
              refreshing ? null : (
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
              )
            }
          >
            刷新
          </Button>
        </div>
      </div>

      {/* 统计范围切换 */}
      <div className="flex flex-wrap items-center gap-2 mb-6">
        {RANGE_OPTIONS.map(option => (
          <Button
            key={option.key}
            size="sm"
            variant={range === option.key ? "solid" : "flat"}
            color={range === option.key ? "primary" : "default"}
            onPress={() => setRange(option.key)}
          >
            {option.label}
          </Button>
        ))}
      </div>

      {/* 汇总信息 */}
      <div className="grid grid-cols-2 gap-3 mb-6 lg:grid-cols-4">
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="p-4">
            <p className="text-xs text-default-500 mb-1">总流量（{trafficScopeLabel}）</p>
            <p className="text-xl font-semibold text-foreground break-all">
              {formatFlow(ranking?.totalFlow)}
            </p>
            <p className="text-xs text-default-400 mt-1">
              入站 {formatFlow(ranking?.totalInFlow)} · 出站 {formatFlow(ranking?.totalOutFlow)}
            </p>
          </CardBody>
        </Card>
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="p-4">
            <p className="text-xs text-default-500 mb-1">参与排行端口</p>
            <p className="text-xl font-semibold text-foreground">
              {ranking?.portCount ?? 0}
              <span className="text-sm font-normal text-default-500 ml-1">
                / {ranking?.forwardCount ?? 0} 条转发
              </span>
            </p>
            <p className="text-xs text-default-400 mt-1">区间内有流量记录的端口</p>
          </CardBody>
        </Card>
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="p-4">
            <p className="text-xs text-default-500 mb-1">统计区间</p>
            <p className="text-sm font-semibold text-foreground">
              {ranking?.rangeLabel || "今天"}
            </p>
            <p className="text-xs text-default-400 mt-1">
              {ranking ? `开始 ${ranking.startTimeText}` : "--"}
            </p>
            <p className="text-xs text-default-400">
              {ranking ? `结束 ${ranking.endTimeText}` : ""}
            </p>
          </CardBody>
        </Card>
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="p-4">
            <p className="text-xs text-default-500 mb-1">最近采集时间</p>
            <p className="text-sm font-semibold text-foreground">
              {ranking?.lastCollectTimeText || "暂无采集"}
            </p>
            <p className="text-xs text-default-400 mt-1">
              采集间隔 {ranking?.sampleIntervalMinutes || 5} 分钟
            </p>
          </CardBody>
        </Card>
      </div>

      {/* 排行榜 */}
      {loading ? (
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>
      ) : loadFailed && items.length === 0 ? (
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="py-12">
            <div className="flex flex-col items-center gap-3 text-center">
              <p className="text-default-600">排行榜加载失败</p>
              <Button size="sm" variant="flat" color="primary" onPress={() => loadRanking("loading")}>
                重新加载
              </Button>
            </div>
          </CardBody>
        </Card>
      ) : items.length === 0 ? (
        <Card className="border border-default-200 dark:border-default-100/10">
          <CardBody className="py-12">
            <div className="flex flex-col items-center gap-2 text-center">
              <svg className="w-12 h-12 text-default-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z" />
              </svg>
              <p className="text-default-600 font-medium">该统计范围内暂无流量数据</p>
              <p className="text-sm text-default-400 max-w-md">
                采集任务每 {ranking?.sampleIntervalMinutes || 5} 分钟运行一次，新部署或端口刚有流量时，
                需要等待一个采集周期后才会出现在排行榜中。
              </p>
            </div>
          </CardBody>
        </Card>
      ) : (
        <div className="space-y-2">
          {items.map(item => {
            const percent = maxFlow > 0 ? Math.max((item.totalFlow / maxFlow) * 100, 1.5) : 0;
            const ports = item.ports || [];
            const multiplePorts = ports.length > 1;
            return (
              <Card
                key={item.forwardId}
                className="border border-default-200 dark:border-default-100/10 hover:border-primary-200 transition-colors"
              >
                <CardBody className="p-3 lg:p-4">
                  <div className="flex items-start gap-3">
                    <div
                      className={`flex items-center justify-center w-8 h-8 rounded-full text-sm font-semibold flex-shrink-0 ${getRankStyle(item.rank)}`}
                      title={`第 ${item.rank} 名`}
                    >
                      {item.rank}
                    </div>

                    <div className="flex-1 min-w-0">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <span className="font-semibold text-foreground truncate max-w-[16rem]">
                          {item.forwardName}
                        </span>
                        <code className="text-xs font-mono px-1.5 py-0.5 rounded bg-default-100 dark:bg-default-200/20 text-foreground">
                          端口 {formatPort(item)}
                        </code>
                        {item.deleted && (
                          <Chip size="sm" variant="flat" color="default" className="text-xs">已删除</Chip>
                        )}
                        {!item.deleted && item.status === 0 && (
                          <Chip size="sm" variant="flat" color="warning" className="text-xs">已暂停</Chip>
                        )}
                        {multiplePorts && (
                          <span className="text-xs text-default-400 break-all">
                            全部端口：{ports.join(", ")}
                          </span>
                        )}
                      </div>

                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 mt-1 text-xs text-default-500">
                        <span>{item.tunnelName || "未知隧道"}</span>
                        {item.userName && <span>用户：{item.userName}</span>}
                        <span className="text-default-400">采集点 {item.sampleCount}</span>
                        {item.lastTime && (
                          <span className="text-default-400">
                            最近 {new Date(item.lastTime).toLocaleString("zh-CN", { hour12: false })}
                          </span>
                        )}
                      </div>

                      {/* 流量占比条 */}
                      <div className="mt-2 w-full h-2 rounded-full bg-default-100 dark:bg-default-200/20 overflow-hidden">
                        <div
                          className="h-2 rounded-full bg-gradient-to-r from-primary-400 to-primary-600"
                          style={{ width: `${percent}%` }}
                        />
                      </div>
                    </div>

                    <div className="flex flex-col items-end flex-shrink-0 ml-1">
                      <span className="text-base lg:text-lg font-semibold text-foreground">
                        {formatFlow(item.totalFlow)}
                      </span>
                      <div className="flex flex-col items-end gap-0.5 mt-1">
                        <span className="text-xs text-primary-600 dark:text-primary-400">
                          入站 {formatFlow(item.inFlow)}
                        </span>
                        <span className="text-xs text-success-600 dark:text-success-400">
                          出站 {formatFlow(item.outFlow)}
                        </span>
                      </div>
                    </div>
                  </div>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}

      {/* 统计口径说明 */}
      <div className="mt-6 text-xs text-default-400 leading-relaxed">
        <p>
          统计口径：总流量 = 入站流量 + 出站流量，取自转发计数器（forward.in_flow / forward.out_flow）的累计值，
          已按隧道流量倍率与单向/双向计费方式换算，与面板其他页面的流量用量口径一致。
        </p>
        <p>
          区间归属以采集点时间为准（{ranking?.sampleIntervalMinutes || 5} 分钟粒度）；计数器重置时按重置后的累计值计入，
          不会产生负值；服务停机期间产生的流量会在恢复后的第一次采集中补齐，不会重复累计。
        </p>
        {ranking?.generatedTime && (
          <p className="mt-1">数据生成时间：{new Date(ranking.generatedTime).toLocaleString("zh-CN", { hour12: false })}</p>
        )}
      </div>
    </div>
  );
}

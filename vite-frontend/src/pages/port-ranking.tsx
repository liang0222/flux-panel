import { useState, useEffect, useCallback } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { toast } from "react-hot-toast";

import { getPortRanking, type PortRankingItem } from "@/api";
type RangeKey = "today" | "3d" | "7d";

const RANGE_OPTIONS: Array<{ key: RangeKey; label: string }> = [
  { key: "today", label: "今天" },
  { key: "3d", label: "三天" },
  { key: "7d", label: "七天" },
];

/** 字节数格式化为可读单位 */
const formatFlow = (value: number): string => {
  if (!value || value <= 0) return "0 B";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(2)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(2)} MB`;
  if (value < 1024 * 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  return `${(value / (1024 * 1024 * 1024 * 1024)).toFixed(2)} TB`;
};

/** 排名徽标配色：前三名做区分 */
const rankBadgeClass = (rank: number): string => {
  if (rank === 1) return "bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300";
  if (rank === 2) return "bg-slate-200 text-slate-700 dark:bg-slate-500/20 dark:text-slate-300";
  if (rank === 3) return "bg-orange-100 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300";
  return "bg-default-100 text-default-600";
};

export default function PortRankingPage() {
  const [range, setRange] = useState<RangeKey>("today");
  const [list, setList] = useState<PortRankingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [isAdmin, setIsAdmin] = useState(false);

  useEffect(() => {
    setIsAdmin(localStorage.getItem("admin") === "true");
  }, []);

  const loadData = useCallback(async (key: RangeKey) => {
    setLoading(true);
    try {
      const res = await getPortRanking(key);

      if (res.code === 0) {
        setList(res.data?.list || []);
      } else {
        setList([]);
        toast.error(res.msg || "获取端口流量排行失败");
      }
    } catch (error) {
      console.error("获取端口流量排行失败:", error);
      setList([]);
      toast.error("获取端口流量排行失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    localStorage.setItem("e", "/port-ranking");
    loadData(range);
  }, [range, loadData]);

  // 用于进度条宽度：以榜首流量为基准
  const maxFlow = list.length > 0 ? Math.max(...list.map((item) => item.flow || 0)) : 0;
  const totalFlow = list.reduce((sum, item) => sum + (item.flow || 0), 0);

  return (
    <div className="p-4 lg:p-6 space-y-4">
      {/* 标题与区间切换 */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl lg:text-2xl font-bold text-foreground">端口流量排行榜</h1>
          <p className="text-sm text-default-500 mt-1">
            {isAdmin ? "统计全部转发的端口用量" : "统计你名下转发的端口用量"}
          </p>
        </div>

        <div className="flex items-center gap-2">
          {RANGE_OPTIONS.map((option) => (
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
          <Button
            size="sm"
            variant="flat"
            isLoading={loading}
            onPress={() => loadData(range)}
          >
            刷新
          </Button>
        </div>
      </div>

      {/* 汇总卡片 */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <Card>
          <CardBody className="py-3">
            <p className="text-xs text-default-500">端口数量</p>
            <p className="text-lg font-bold text-foreground">{list.length}</p>
          </CardBody>
        </Card>
        <Card>
          <CardBody className="py-3">
            <p className="text-xs text-default-500">区间总流量</p>
            <p className="text-lg font-bold text-primary">{formatFlow(totalFlow)}</p>
          </CardBody>
        </Card>
        <Card>
          <CardBody className="py-3">
            <p className="text-xs text-default-500">用量最高端口</p>
            <p className="text-lg font-bold text-foreground truncate">
              {list.length > 0 ? (list[0].inPort ?? "-") : "-"}
            </p>
          </CardBody>
        </Card>
      </div>

      {/* 排行榜 */}
      <Card>
        <CardHeader className="flex flex-col items-start gap-1 pb-0">
          <h2 className="text-base lg:text-lg font-semibold text-foreground">
            {RANGE_OPTIONS.find((item) => item.key === range)?.label}排行
          </h2>
          <p className="text-xs text-default-400">
            按转发流量降序排列，数据每小时采集一次
          </p>
        </CardHeader>
        <CardBody>
          {loading ? (
            <div className="flex justify-center py-12">
              <Spinner label="加载中..." />
            </div>
          ) : list.length === 0 ? (
            <div className="text-center py-12 text-default-500">
              <p>暂无流量数据</p>
              <p className="text-xs mt-2 text-default-400">
                流量快照每小时采集一次，请在下一个整点后再查看
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {list.map((item) => {
                const percentage = maxFlow > 0 ? ((item.flow || 0) / maxFlow) * 100 : 0;
                return (
                  <div
                    key={item.forwardId}
                    className="relative overflow-hidden rounded-lg border border-default-200 dark:border-default-100 p-3"
                  >
                    {/* 背景进度条 */}
                    <div
                      className="absolute inset-y-0 left-0 bg-primary-50 dark:bg-primary-500/10 transition-all"
                      style={{ width: `${percentage}%` }}
                    />
                    <div className="relative flex items-center gap-3">
                      <span
                        className={`flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold ${rankBadgeClass(item.rank)}`}
                      >
                        {item.rank}
                      </span>

                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-semibold text-foreground truncate">
                            {item.name || `转发 #${item.forwardId}`}
                          </span>
                          <Chip size="sm" variant="flat" color="primary">
                            端口 {item.inPort ?? "-"}
                          </Chip>
                          {item.status !== null && item.status !== 1 && (
                            <Chip size="sm" variant="flat" color="warning">
                              已暂停
                            </Chip>
                          )}
                        </div>
                        <div className="text-xs text-default-500 mt-1 truncate">
                          {isAdmin && item.userName ? `用户：${item.userName}` : ""}
                          {isAdmin && item.userName && item.tunnelName ? " · " : ""}
                          {item.tunnelName ? `隧道：${item.tunnelName}` : ""}
                        </div>
                      </div>

                      <div className="flex-shrink-0 text-right">
                        <p className="font-bold text-primary">{formatFlow(item.flow || 0)}</p>
                        <p className="text-xs text-default-400">
                          {totalFlow > 0 ? ((item.flow / totalFlow) * 100).toFixed(1) : "0.0"}%
                        </p>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

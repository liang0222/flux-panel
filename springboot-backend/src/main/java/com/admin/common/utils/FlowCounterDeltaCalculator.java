package com.admin.common.utils;

/**
 * 转发流量计数器差额计算器
 * <p>
 * 项目现有流量口径：节点每 5 秒上报增量，面板把增量累加到 forward.in_flow / forward.out_flow
 * （已在 FlowController 中按隧道倍率 traffic_ratio 与计费方式 flow 换算），因此这两个字段是
 * 单调递增的累计计数器。本工具按相邻两次采集值计算新增流量：
 * <ul>
 *     <li>首次采集：只记录基线，新增为 0，避免把开启统计前的历史累计值算进当前区间；</li>
 *     <li>正常情况：新增 = 当前值 - 上次值，两个方向分别计算；</li>
 *     <li>计数器回退（重置、数据回滚）：新增 = 当前值（视为从 0 重新开始），且不会产生负值；</li>
 *     <li>采集缺失（服务停机后恢复）：上次采集值仍在数据库中，差额会覆盖缺失期间流量，
 *         不会重复累计，因为始终以最近一次落库值为基线。</li>
 * </ul>
 *
 * @since 2.0.7-beta
 */
public final class FlowCounterDeltaCalculator {

    private FlowCounterDeltaCalculator() {
    }

    /**
     * 计算相邻两次采集之间的新增流量
     *
     * @param previousInFlow  上次采集的累计入站流量，为空表示该转发没有历史采集记录
     * @param previousOutFlow 上次采集的累计出站流量，为空表示该转发没有历史采集记录
     * @param currentInFlow   当前累计入站流量
     * @param currentOutFlow  当前累计出站流量
     * @return 新增流量与重置标记
     */
    public static FlowCounterDelta calculate(Long previousInFlow, Long previousOutFlow,
                                             Long currentInFlow, Long currentOutFlow) {
        long currentIn = nonNegative(currentInFlow);
        long currentOut = nonNegative(currentOutFlow);

        // 首次采集：仅建立基线，不把历史累计值计入本次区间
        if (previousInFlow == null && previousOutFlow == null) {
            return new FlowCounterDelta(0L, 0L, false);
        }

        long previousIn = nonNegative(previousInFlow);
        long previousOut = nonNegative(previousOutFlow);

        boolean reset = currentIn < previousIn || currentOut < previousOut;

        // 每个方向独立判断：回退时按“重置后从 0 累计”处理，取当前值作为新增
        long inDelta = currentIn >= previousIn ? currentIn - previousIn : currentIn;
        long outDelta = currentOut >= previousOut ? currentOut - previousOut : currentOut;

        return new FlowCounterDelta(inDelta, outDelta, reset);
    }

    private static long nonNegative(Long value) {
        if (value == null) {
            return 0L;
        }
        return Math.max(value, 0L);
    }

    /**
     * 新增流量计算结果
     */
    public static final class FlowCounterDelta {

        private final long inDelta;

        private final long outDelta;

        private final boolean reset;

        public FlowCounterDelta(long inDelta, long outDelta, boolean reset) {
            this.inDelta = inDelta;
            this.outDelta = outDelta;
            this.reset = reset;
        }

        public long getInDelta() {
            return inDelta;
        }

        public long getOutDelta() {
            return outDelta;
        }

        public boolean isReset() {
            return reset;
        }

        /**
         * 新增总流量（入站 + 出站）
         */
        public long getTotalDelta() {
            return inDelta + outDelta;
        }
    }

}

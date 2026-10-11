package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 端口（转发）级流量快照。
 *
 * <p>由 {@code StatisticsFlowForwardAsync} 每 5 分钟采集一次，用于「端口流量排行榜」。
 * 每行记录「某个转发在某个采集时刻的增量流量」与「当时的累计流量」。
 *
 * <p>本实体为新增功能，不影响原版任何逻辑。
 */
@Data
@TableName("statistics_flow_forward")
public class StatisticsFlowForward {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 转发ID（forward.id） */
    private Long forwardId;

    /** 转发所属用户ID（与 Forward.userId 类型保持一致） */
    private Integer userId;

    /** 转发名称（冗余存储，转发删除后仍可展示） */
    private String name;

    /** 入口端口（冗余存储） */
    private Integer inPort;

    /** 落地目标地址（冗余存储） */
    private String remoteAddr;

    /** 所属隧道ID（与 Forward.tunnelId 类型保持一致） */
    private Integer tunnelId;

    /** 本次采集周期内的增量流量（字节） */
    private Long flow;

    /** 采集时刻的累计流量（字节），用于下一周期计算增量 */
    private Long totalFlow;

    /** 采集时刻的毫秒时间戳 */
    private Long createdTime;
}

package com.admin.common.dto;

import lombok.Data;

/**
 * <p>
 * 端口转发流量排行榜查询参数
 * </p>
 *
 * @since 2.0.7-beta
 */
@Data
public class ForwardFlowQueryDto {

    /**
     * 统计范围：today / 3d / 7d / 30d，缺省为 today
     */
    private String range;

}

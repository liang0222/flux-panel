package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import java.math.BigDecimal;

@Data
public class TunnelUpdateDto {

    @NotNull(message = "隧道ID不能为空")
    private Long id;

    @NotBlank(message = "隧道名称不能为空")
    private String name;

    @NotNull(message = "流量计算类型不能为空")
    private Integer flow;

    private String inIp;

    @DecimalMin(value = "0.0", inclusive = false, message = "流量倍率必须大于0.0")
    @DecimalMax(value = "100.0", message = "流量倍率不能大于100.0")
    private BigDecimal trafficRatio;
}
package com.admin.common.dto;

import com.admin.entity.ChainTunnel;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.util.List;

@Data
public class TunnelDto {
    
    @NotBlank(message = "隧道名称不能为空")
    private String name;

    @NotNull(message = "入口节点不能为空")
    private List<ChainTunnel> inNodeId;

    private List<List<ChainTunnel>> chainNodes;

    private List<ChainTunnel> outNodeId;

    private String inIp;

    @NotNull(message = "隧道类型不能为空")
    private Integer type;
    
    @NotNull(message = "流量计算类型不能为空")
    private Integer flow;
    
    @DecimalMin(value = "0.0", inclusive = false, message = "流量倍率必须大于0.0")
    @DecimalMax(value = "100.0", message = "流量倍率不能大于100.0")
    private BigDecimal trafficRatio;
} 
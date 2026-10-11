package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class NodeUpdateDto {

    @NotNull(message = "节点ID不能为空")
    private Long id;

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @NotBlank(message = "服务器ip不能为空")
    private String serverIp;

    @NotBlank(message = "可用port不能为空")
    private String port;

    private String interfaceName;
    private Integer http;
    private Integer tls;
    private Integer socks;

    private String tcpListenAddr = "0.0.0.0";

    private String udpListenAddr = "0.0.0.0";
} 
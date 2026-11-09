package com.admin.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 隧道实体类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(autoResultMap = true)
public class Tunnel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private Integer type;

    private int flow;

    private BigDecimal trafficRatio;

    private String inIp;
}

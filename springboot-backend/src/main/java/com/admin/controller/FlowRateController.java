package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.FlowRateService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 实时速率控制器
 * </p>
 * 管理员可查看全部转发，普通用户只返回自己的转发；数据来自节点每 5 秒的上报增量，不落库。
 *
 * @since 2.0.7-beta
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/flow-rate")
public class FlowRateController extends BaseController {

    @Resource
    FlowRateService flowRateService;

    /**
     * 当前实时速率
     *
     * @return 总速率、入站/出站速率与占比、最忙端口、最高用户
     */
    @PostMapping("/current")
    public R current() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        boolean admin = roleId != null && roleId == 0;
        return R.ok(flowRateService.getCurrentRate(userId, admin));
    }

}

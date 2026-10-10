package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.lang.R;
import javax.servlet.http.HttpServletResponse;

import com.admin.common.utils.PasswordUtil;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;


@RestController
@CrossOrigin
@RequestMapping("/api/v1/open_api")
public class OpenApiController extends BaseController {

    @LogAnnotation
    @GetMapping("/sub_store")
    public Object create(
            @RequestParam("user") String user,
            @RequestParam("pwd") String pwd,
            @RequestParam(value = "tunnel", required = false, defaultValue = "-1") String tunnel,
            HttpServletResponse response) {
        JSONObject result = new JSONObject();
        result.put("upload", 0);
        result.put("download", 0);
        result.put("total", 0);
        result.put("expire", 0);
        // 校验 user 是否为空
        if (user == null || user.isEmpty()) {
            return R.err("用户不能为空");
        }
        if (pwd == null || pwd.isEmpty()) {
            return R.err("密码不能为空");
        }

        User userInfo = userService.getOne(new QueryWrapper<User>().eq("user", user));
        if (userInfo == null) {
            return R.err("鉴权失败");
        }

        // 必须用 PasswordUtil.verify：兼容历史无盐 MD5，也支持现行的 PBKDF2 哈希。
        // 原先直接比较 Md5Util.md5(pwd)，而用户首次登录成功后密码会被
        // upgradeLegacyPasswordIfNeeded() 透明改写为 PBKDF2，导致 MD5 比较永远不成立，
        // 该接口对升级过的用户彻底失效。
        if (!PasswordUtil.verify(pwd, userInfo.getPwd())) {
            return R.err("鉴权失败");
        }

        final long GIGA = 1024L * 1024L * 1024L;
        String headerValue;

        if ("-1".equals(tunnel)) {
            headerValue = buildSubscriptionHeader(
                    userInfo.getOutFlow(),
                    userInfo.getInFlow(),
                    userInfo.getFlow() * GIGA,
                    userInfo.getExpTime() / 1000
            );
        } else {
            UserTunnel tunnelInfo = userTunnelService.getById(tunnel);
            if (tunnelInfo == null) return R.err("隧道不存在");
            if (!tunnelInfo.getUserId().toString().equals(userInfo.getId().toString())) return R.err("隧道不存在");
            headerValue = buildSubscriptionHeader(
                    tunnelInfo.getOutFlow(),
                    tunnelInfo.getInFlow(),
                    tunnelInfo.getFlow() * GIGA,
                    tunnelInfo.getExpTime() / 1000
            );
        }

        response.setHeader("subscription-userinfo", headerValue);
        return headerValue;
    }



    private String buildSubscriptionHeader(long upload, long download, long total, long expire) {
        return String.format("upload=%d; download=%d; total=%d; expire=%d", download, upload, total, expire);
    }


}

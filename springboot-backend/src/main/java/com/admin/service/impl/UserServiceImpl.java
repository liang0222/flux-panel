package com.admin.service.impl;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.spring.plugins.secondary.SecondaryVerificationApplication;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.Md5Util;
import com.admin.entity.*;
import com.admin.mapper.UserMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    @Lazy
    ForwardService forwardService;

    @Resource
    UserMapper userMapper;

    @Resource
    @Lazy
    TunnelService tunnelService;
    
    @Resource
    @Lazy
    NodeService nodeService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    StatisticsFlowService statisticsFlowService;

    @Resource
    @Lazy
    ForwardPortService forwardPortService;

    @Resource
    ImageCaptchaApplication application;


    @Override
    public R login(LoginDto loginDto) {
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "captcha_enabled"));
        if (viteConfig != null && Objects.equals(viteConfig.getValue(), "true")) {
            if (StringUtils.isBlank(loginDto.getCaptchaId())) return R.err("验证码校验失败");
            boolean valid = ((SecondaryVerificationApplication) application).secondaryVerification(loginDto.getCaptchaId());
            if (!valid)  return R.err("验证码校验失败");
        }

        User user = this.getOne(new QueryWrapper<User>().eq("user", loginDto.getUsername()));
        if (user == null) return R.err("账号或密码错误");
        if (!user.getPwd().equals(Md5Util.md5(loginDto.getPassword())))  return R.err("账号或密码错误");
        if (user.getStatus() == 0)  return R.err("账号被停用");
        String token = JwtUtil.generateToken(user);
        boolean requirePasswordChange = Objects.equals(loginDto.getUsername(), "admin_user") || Objects.equals(loginDto.getPassword(), "admin_user");
        return R.ok(MapUtil.builder()
                .put("token", token)
                .put("name", user.getUser())
                .put("role_id", user.getRoleId())
                .put("requirePasswordChange", requirePasswordChange)
                .build());
    }

    @Override
    public R createUser(UserDto userDto) {
        int count = this.count(new QueryWrapper<User>().eq("user", userDto.getUser()));
        if (count > 0) return R.err("用户名已存在");
        User user = new User();
        BeanUtils.copyProperties(userDto, user);
        user.setPwd(Md5Util.md5(userDto.getPwd()));
        user.setStatus(1);
        user.setRoleId(1);
        long currentTime = System.currentTimeMillis();
        user.setCreatedTime(currentTime);
        user.setUpdatedTime(currentTime);
        this.save(user);
        return R.ok();
    }

    @Override
    public R getAllUsers() {
        List<User> list = this.list(new QueryWrapper<User>().ne("role_id", 0));
        return R.ok(list);
    }

    @Override
    public R updateUser(UserUpdateDto userUpdateDto) {
        User user = this.getById(userUpdateDto.getId());
        if (user == null) return R.err("用户不存在");
        if (user.getRoleId() == 0) return R.err("请不要作死");

        int count = this.count(new QueryWrapper<User>().eq("user", userUpdateDto.getUser()).ne("id", userUpdateDto.getId()));
        if (count > 0) return R.err("用户名已存在");


        User updateUser = new User();
        BeanUtils.copyProperties(userUpdateDto, updateUser);
        if (StrUtil.isNotBlank(userUpdateDto.getPwd())) {
            updateUser.setPwd(Md5Util.md5(userUpdateDto.getPwd()));
        } else {
            updateUser.setPwd(null); // 不更新密码字段
        }
        updateUser.setUpdatedTime(System.currentTimeMillis());
        this.updateById(updateUser);
        return R.ok();
    }

    @Override
    public R deleteUser(Long id) {
        User user = this.getById(id);
        if (user == null) return R.err("用户不存在");
        if (user.getRoleId() == 0) return R.err("请不要作死");
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", id));
        for (Forward forward : forwardList) {
            forwardService.deleteForward(forward.getId());
        }
        forwardService.remove(new QueryWrapper<Forward>().eq("user_id", id));
        userTunnelService.remove(new QueryWrapper<UserTunnel>().eq("user_id", id));
        statisticsFlowService.remove(new QueryWrapper<StatisticsFlow>().eq("user_id", id));
        this.removeById(id);
        return R.ok();
    }

    @Override
    public R getUserPackageInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        User user = this.getById(userId);
        if (user == null) return R.err("用户不存在");
        UserPackageDto.UserInfoDto userInfo = buildUserInfoDto(user);
        List<UserPackageDto.UserTunnelDetailDto> tunnelPermissions = userMapper.getUserTunnelDetails(userId);
        List<UserPackageDto.UserForwardDetailDto> forwards = userMapper.getUserForwardDetails(user.getId().intValue());
        fillForwardInIpAndPort(forwards);
        List<StatisticsFlow> statisticsFlows = getLast24HoursFlowStatistics(user.getId());
        UserPackageDto packageDto = new UserPackageDto();
        packageDto.setUserInfo(userInfo);
        packageDto.setTunnelPermissions(tunnelPermissions);
        packageDto.setForwards(forwards);
        packageDto.setStatisticsFlows(statisticsFlows);
        return R.ok(packageDto);
    }

    @Override
    public R updatePassword(ChangePasswordDto changePasswordDto) {
        Integer userId = JwtUtil.getUserIdFromToken();
        User user = this.getById(userId);
        if (user == null) return R.err("用户不存在");
        if (!changePasswordDto.getNewPassword().equals(changePasswordDto.getConfirmPassword())) {
            return R.err("新密码和确认密码不匹配");
        }
        String currentPasswordMd5 = Md5Util.md5(changePasswordDto.getCurrentPassword());
        if (!user.getPwd().equals(currentPasswordMd5)) {
            return R.err("当前密码错误");
        }
        if (!user.getUser().equals(changePasswordDto.getNewUsername())) {
            user.setPwd(Md5Util.md5(changePasswordDto.getNewPassword()));
            int count = this.count(new QueryWrapper<User>().eq("user", changePasswordDto.getNewUsername()).ne("id", user.getId()));
            if (count > 0) return R.err("用户名已存在");
        }
        User updateUser = new User();
        updateUser.setId(user.getId());
        updateUser.setUser(changePasswordDto.getNewUsername());
        updateUser.setPwd(Md5Util.md5(changePasswordDto.getNewPassword()));
        updateUser.setUpdatedTime(System.currentTimeMillis());
        this.updateById(updateUser);
        return R.ok();
    }

    @Override
    public R reset(ResetFlowDto resetFlowDto) {
        if (resetFlowDto.getType() == 1){ // 清零账号流量
            User user = this.getById(resetFlowDto.getId());
            if (user == null) return R.err("用户不存在");
            user.setInFlow(0L);
            user.setOutFlow(0L);
            this.updateById(user);
        }else { // 清零隧道流量
            UserTunnel tunnel = userTunnelService.getById(resetFlowDto.getId());
            if (tunnel == null) return R.err("隧道不存在");
            tunnel.setInFlow(0L);
            tunnel.setOutFlow(0L);
            userTunnelService.updateById(tunnel);
        }
        return R.ok();
    }

    private UserPackageDto.UserInfoDto buildUserInfoDto(User user) {
        UserPackageDto.UserInfoDto userInfo = new UserPackageDto.UserInfoDto();
        userInfo.setId(user.getId());
        userInfo.setUser(user.getUser());
        userInfo.setStatus(user.getStatus());
        userInfo.setFlow(user.getFlow());
        userInfo.setInFlow(user.getInFlow());
        userInfo.setOutFlow(user.getOutFlow());
        userInfo.setNum(user.getNum());
        userInfo.setExpTime(user.getExpTime());
        userInfo.setFlowResetTime(user.getFlowResetTime());
        userInfo.setCreatedTime(user.getCreatedTime());
        userInfo.setUpdatedTime(user.getUpdatedTime());
        return userInfo;
    }

    private List<StatisticsFlow> getLast24HoursFlowStatistics(Long userId) {
        List<StatisticsFlow> recentFlows = statisticsFlowService.list(
                new QueryWrapper<StatisticsFlow>()
                        .eq("user_id", userId)
                        .orderByDesc("id")
                        .last("LIMIT 24")
        );
        List<StatisticsFlow> result = new ArrayList<>(recentFlows);
        if (result.size() < 24) {
            int startHour = java.time.LocalDateTime.now().getHour();
            if (!result.isEmpty()) {
                String lastTime = result.getLast().getTime();
                startHour = parseHour(lastTime) - 1;
            }
            while (result.size() < 24) {
                if (startHour < 0) startHour = 23;
                StatisticsFlow emptyFlow = new StatisticsFlow();
                emptyFlow.setUserId(userId);
                emptyFlow.setFlow(0L);
                emptyFlow.setTotalFlow(0L);
                emptyFlow.setTime(String.format("%02d:00", startHour));
                result.add(emptyFlow);
                startHour--;
            }
        }
        return result;
    }

    private int parseHour(String timeStr) {
        if (timeStr != null && timeStr.contains(":")) {
            return Integer.parseInt(timeStr.split(":")[0]);
        }
        return java.time.LocalDateTime.now().getHour();
    }

    private void fillForwardInIpAndPort(List<UserPackageDto.UserForwardDetailDto> forwards) {
        for (UserPackageDto.UserForwardDetailDto forward : forwards) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) continue;
            
            List<ForwardPort> forwardPorts = forwardPortService.list(
                    new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId())
            );
            if (forwardPorts.isEmpty()) continue;
            
            boolean useTunnelInIp = tunnel.getInIp() != null && !tunnel.getInIp().trim().isEmpty();
            java.util.Set<String> ipPortSet = new java.util.LinkedHashSet<>();
            
            if (useTunnelInIp) {
                // 使用隧道的inIp（求笛卡尔积）
                List<String> ipList = new ArrayList<>();
                List<Integer> portList = new ArrayList<>();
                
                String[] tunnelInIps = tunnel.getInIp().split(",");
                for (String ip : tunnelInIps) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        ipList.add(ip.trim());
                    }
                }
                
                for (ForwardPort forwardPort : forwardPorts) {
                    if (forwardPort.getPort() != null) {
                        portList.add(forwardPort.getPort());
                    }
                }
                
                List<String> uniqueIps = ipList.stream().distinct().toList();
                List<Integer> uniquePorts = portList.stream().distinct().toList();
                
                for (String ip : uniqueIps) {
                    for (Integer port : uniquePorts) {
                        ipPortSet.add(ip + ":" + port);
                    }
                }
                
                if (!uniquePorts.isEmpty()) {
                    forward.setInPort(uniquePorts.getFirst());
                }
            } else {
                // 使用节点的serverIp（一对一，不求笛卡尔积）
                for (ForwardPort forwardPort : forwardPorts) {
                    Node node = nodeService.getById(forwardPort.getNodeId());
                    if (node != null && node.getServerIp() != null && forwardPort.getPort() != null) {
                        ipPortSet.add(node.getServerIp() + ":" + forwardPort.getPort());
                    }
                }
                
                if (!forwardPorts.isEmpty() && forwardPorts.getFirst().getPort() != null) {
                    forward.setInPort(forwardPorts.getFirst().getPort());
                }
            }
            
            if (!ipPortSet.isEmpty()) {
                forward.setInIp(String.join(",", ipPortSet));
            }
        }
    }

}

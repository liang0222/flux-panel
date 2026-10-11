package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户隧道权限服务实现类
 * 提供用户隧道权限的分配、查询、更新和删除功能
 * 支持流量限制、数量限制、过期时间和限速规则的管理
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class UserTunnelServiceImpl extends ServiceImpl<UserTunnelMapper, UserTunnel> implements UserTunnelService {


    @Resource
    @Lazy
    private ForwardService forwardService;

    @Override
    public R assignUserTunnel(UserTunnelDto userTunnelDto) {
        int count = this.count(new QueryWrapper<UserTunnel>().eq("user_id", userTunnelDto.getUserId()).eq("tunnel_id", userTunnelDto.getTunnelId()));
        if (count > 0) return R.err("该用户已拥有此隧道权限");
        UserTunnel userTunnel = new UserTunnel();
        BeanUtils.copyProperties(userTunnelDto, userTunnel);
        userTunnel.setStatus(1);
        this.save(userTunnel);
        return R.ok();
    }

    @Override
    public R getUserTunnelList(UserTunnelQueryDto queryDto) {
        List<UserTunnelWithDetailDto> userTunnelWithDetails = this.baseMapper.getUserTunnelWithDetails(queryDto.getUserId());
        return R.ok(userTunnelWithDetails);
    }

    @Override
    public R removeUserTunnel(Integer id) {
        UserTunnel userTunnel = this.getById(id);
        if (userTunnel == null) return R.err("未找到对应的用户隧道权限记录");

        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userTunnel.getUserId()).eq("tunnel_id", userTunnel.getTunnelId()));
        for (Forward forward : forwardList) {
            forwardService.deleteForward(forward.getId());
        }
        this.removeById(id);
        return R.ok();
    }

    @Override
    public R updateUserTunnel(UserTunnelUpdateDto updateDto) {
        UserTunnel userTunnel = this.getById(updateDto.getId());
        if (userTunnel == null) return R.err("隧道不存在");
        boolean speedChanged = hasSpeedChanged(userTunnel.getSpeedId(), updateDto.getSpeedId());
        userTunnel.setFlow(updateDto.getFlow());
        userTunnel.setNum(updateDto.getNum());
        updateOptionalProperty(userTunnel::setFlowResetTime, updateDto.getFlowResetTime());
        updateOptionalProperty(userTunnel::setExpTime, updateDto.getExpTime());
        updateOptionalProperty(userTunnel::setStatus, updateDto.getStatus());
        userTunnel.setSpeedId(updateDto.getSpeedId());
        this.updateById(userTunnel);
        if (speedChanged) {
            List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userTunnel.getUserId()).eq("tunnel_id", userTunnel.getTunnelId()));
            for (Forward forward : forwardList) {
                ForwardUpdateDto forwardUpdateDto = new ForwardUpdateDto();
                forwardUpdateDto.setId(forward.getId());
                forwardUpdateDto.setUserId(forward.getUserId());
                forwardUpdateDto.setName(forward.getName());
                forwardUpdateDto.setRemoteAddr(forward.getRemoteAddr());
                forwardUpdateDto.setStrategy(forward.getStrategy());
                forwardService.updateForward(forwardUpdateDto);
            }
        }
        return R.err("用户隧道权限更新失败");
    }

    private <T> void updateOptionalProperty(java.util.function.Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private boolean hasSpeedChanged(Integer oldSpeedId, Integer newSpeedId) {
        if (oldSpeedId == null && newSpeedId == null) {
            return false;
        }
        if (oldSpeedId == null || newSpeedId == null) {
            return true;
        }
        return !oldSpeedId.equals(newSpeedId);
    }

}

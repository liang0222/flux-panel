package com.admin.common.utils;

import com.admin.common.dto.GostDto;

/**
 * 向节点下发命令的抽象，便于在单元测试中替换 WebSocket 通道。
 */
@FunctionalInterface
public interface NodeCommandSender {

    GostDto send(Long nodeId, Object payload, String type);
}

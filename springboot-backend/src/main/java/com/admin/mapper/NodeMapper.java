package com.admin.mapper;

import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface NodeMapper extends BaseMapper<Node> {

    /**
     * 判断节点密钥是否存在（只取一行，不做计数）
     *
     * @param secret 节点密钥
     * @return 命中返回 1，未命中返回 null
     */
    Integer existsBySecret(@Param("secret") String secret);

}

package com.aegis.runtime.service.policy;

import com.aegis.core.domain.security.HitlNode;
import com.aegis.dal.mapper.security.HitlNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * HITL 规则领域服务。
 *
 * <p>收口 {@link HitlNodeMapper} 的数据访问，供 {@code AegisHitlRuleLoader}
 * 等集成层组件调用，避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>查询指定智能体启用的 HitlNode 列表（sec_hitl_node 表）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlRuleService {

    private final HitlNodeMapper hitlNodeMapper;

    /**
     * 查询指定智能体启用的 HitlNode 列表。
     *
     * @param agentId 智能体ID
     * @return 启用的 HitlNode 列表，无数据时返回空列表
     */
    public List<HitlNode> listEnabledNodes(long agentId) {
        return hitlNodeMapper.selectList(new LambdaQueryWrapper<HitlNode>()
                .eq(HitlNode::getAgentId, agentId)
                .eq(HitlNode::getEnabled, true));
    }
}

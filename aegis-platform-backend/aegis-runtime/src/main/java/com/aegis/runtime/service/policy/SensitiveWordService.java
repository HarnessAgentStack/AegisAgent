package com.aegis.runtime.service.policy;

import com.aegis.core.domain.security.SensitiveWord;
import com.aegis.dal.mapper.security.SensitiveWordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 敏感词领域服务。
 *
 * <p>收口 {@link SensitiveWordMapper} 的数据访问，供 {@code AegisContentFilterMiddleware}
 * 等集成层组件调用，避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>查询所有启用的敏感词（sec_sensitive_word 表）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private final SensitiveWordMapper sensitiveWordMapper;

    /**
     * 查询所有启用的敏感词。
     *
     * <p>P1 MW-10 修复：建议使用 {@link #listEnabledWords(Long)} 按租户隔离查询。
     * 本方法保留以兼容旧调用方，内部委托给按租户查询（tenantId 为 null 时不附加过滤条件）。
     *
     * @return 启用的敏感词列表，无数据时返回空列表
     */
    public List<SensitiveWord> listEnabledWords() {
        // P1 MW-10 修复：委托给按租户查询，null 表示不过滤租户
        return listEnabledWords(null);
    }

    /**
     * P1 MW-10 修复：按租户查询启用的敏感词。
     *
     * <p>原 listEnabledWords() 无 tenantId 过滤，会导致租户间敏感词配置互相干扰。
     * 现按 tenantId 隔离查询，确保仅加载当前租户的敏感词。
     *
     * @param tenantId 租户ID（为 null 时不附加租户过滤条件，兼容无上下文场景）
     * @return 启用的敏感词列表，无数据时返回空列表
     */
    public List<SensitiveWord> listEnabledWords(Long tenantId) {
        return sensitiveWordMapper.selectList(
                new LambdaQueryWrapper<SensitiveWord>()
                        .eq(SensitiveWord::getEnabled, true)
                        .eq(tenantId != null, SensitiveWord::getTenantId, tenantId));
    }
}

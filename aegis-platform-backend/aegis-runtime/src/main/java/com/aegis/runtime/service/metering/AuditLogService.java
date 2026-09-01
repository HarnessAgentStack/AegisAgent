package com.aegis.runtime.service.metering;

import com.aegis.core.domain.monitor.AuditLog;
import com.aegis.dal.mapper.monitor.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审计日志领域服务。
 *
 * <p>收口 {@link AuditLogMapper} 的数据访问，供 {@code AegisAuditLogMiddleware}
 * 等集成层组件调用，避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>写入审计日志（mon_audit_log 表）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 插入一条审计日志。
     *
     * @param audit 审计日志实体
     */
    public void writeAuditLog(AuditLog audit) {
        auditLogMapper.insert(audit);
    }
}

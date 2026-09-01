package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxLease;
import com.aegis.dal.mapper.sandbox.SandboxLeaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱租约领域服务（runtime 侧）。
 *
 * <p>提供租约创建、续约、释放、过期处理等核心操作。
 * admin 侧通过 {@link SandboxLeaseMapper} 直接访问租约数据，
 * runtime 侧通过本服务完成租约生命周期管理。
 *
 * <p>重构说明：Phase 1 从 dal 模块上提到 runtime/domain/service，
 * 消除 dal 模块中的业务 @Service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxLeaseService {

    private final SandboxLeaseMapper leaseMapper;

    @Transactional
    public SandboxLease createLease(String instanceId, String sessionId, String slotKey,
                                      long duration, TimeUnit unit) {
        String leaseId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expireAt = LocalDateTime.now().plusSeconds(unit.toSeconds(duration));

        SandboxLease lease = SandboxLease.builder()
                .leaseId(leaseId)
                .instanceId(instanceId)
                .sessionId(sessionId)
                .slotKey(slotKey)
                .expireAt(expireAt)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        leaseMapper.insert(lease);
        log.info("租约已创建: leaseId={}, instanceId={}, sessionId={}, expireAt={}",
                leaseId, instanceId, sessionId, expireAt);
        return lease;
    }

    @Transactional
    public boolean renewLease(String leaseId, long duration, TimeUnit unit) {
        LocalDateTime newExpireAt = LocalDateTime.now().plusSeconds(unit.toSeconds(duration));
        int updated = leaseMapper.renewLease(leaseId, newExpireAt);
        if (updated > 0) {
            log.debug("租约已续租: leaseId={}, newExpireAt={}", leaseId, newExpireAt);
            return true;
        }
        log.warn("租约续租失败: leaseId={}", leaseId);
        return false;
    }

    @Transactional
    public boolean releaseLease(String leaseId, long bufferSeconds) {
        LocalDateTime expireAt = LocalDateTime.now().plusSeconds(bufferSeconds);
        int updated = leaseMapper.releaseLease(leaseId, expireAt);
        if (updated > 0) {
            log.info("租约已释放(软): leaseId={}, expireAt={}", leaseId, expireAt);
            return true;
        }
        log.warn("租约释放失败: leaseId={}", leaseId);
        return false;
    }

    @Transactional
    public int expireAll(LocalDateTime now) {
        List<SandboxLease> expired = leaseMapper.selectExpiredLeases(now);
        int count = 0;
        for (SandboxLease lease : expired) {
            leaseMapper.markExpired(lease.getLeaseId(), now);
            count++;
        }
        if (count > 0) {
            log.info("租约过期处理: 共{}条过期租约", count);
        }
        return count;
    }

    public List<SandboxLease> selectExpiredLeases(LocalDateTime now) {
        return leaseMapper.selectExpiredLeases(now);
    }

    public SandboxLease findByLeaseId(String leaseId) {
        return leaseMapper.selectByLeaseId(leaseId);
    }

    public List<SandboxLease> findActiveByInstanceId(String instanceId) {
        return leaseMapper.selectActiveByInstanceId(instanceId);
    }

    public List<SandboxLease> findActiveBySlotKey(String slotKey) {
        return leaseMapper.selectActiveBySlotKey(slotKey);
    }
}

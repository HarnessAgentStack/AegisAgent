package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.DocumentProcessProgressMapper;
import com.aegis.core.domain.resource.DocumentProcessProgress;
import com.aegis.core.dto.resource.ProcessProgressVO;
import com.aegis.core.enums.resource.ProcessStep;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档处理进度服务。
 *
 * <p>负责文档处理流水线的进度追踪和 SSE 实时推送。
 * 采用内存+数据库双层存储：进度变化实时推送给 SSE 订阅者，同时持久化到数据库供断线恢复。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProgressService {

    private final DocumentProcessProgressMapper progressMapper;

    /** SSE sink 注册表：docId -> FluxSink */
    private final Map<Long, FluxSink<String>> sinkRegistry = new ConcurrentHashMap<>();

    /** 最新进度快照缓存：docId -> 最新进度列表 */
    private final Map<Long, List<ProcessProgressVO>> progressCache = new ConcurrentHashMap<>();

    /**
     * 初始化文档处理进度。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param docId    文档ID
     */
    public void initProgress(Long tenantId, Long kbId, Long docId) {
        progressMapper.delete(new LambdaQueryWrapper<DocumentProcessProgress>()
                .eq(DocumentProcessProgress::getDocId, docId));

        List<DocumentProcessProgress> steps = new ArrayList<>();
        for (ProcessStep step : ProcessStep.values()) {
            if (step == ProcessStep.COMPLETED) continue;

            DocumentProcessProgress progress = DocumentProcessProgress.builder()
                    .kbId(kbId)
                    .docId(docId)
                    .step(step.getCode())
                    .stepOrder(step.getOrder())
                    .status("PENDING")
                    .progressPercent(0)
                    .message(step.getDefaultMessage())
                    .build();
            progress.setTenantId(tenantId);
            steps.add(progress);
        }
        progressMapper.insert(steps);
        log.info("文档处理进度已初始化: docId={}, steps={}", docId, steps.size());
    }

    /**
     * 开始指定步骤。
     *
     * @param docId   文档ID
     * @param step    步骤枚举
     * @param message 步骤描述信息
     */
    public void startStep(Long docId, ProcessStep step, String message) {
        progressMapper.update(null, new LambdaUpdateWrapper<DocumentProcessProgress>()
                .eq(DocumentProcessProgress::getDocId, docId)
                .eq(DocumentProcessProgress::getStep, step.getCode())
                .set(DocumentProcessProgress::getStatus, "RUNNING")
                .set(DocumentProcessProgress::getProgressPercent, 0)
                .set(DocumentProcessProgress::getMessage, message != null ? message : step.getDefaultMessage())
                .set(DocumentProcessProgress::getStartedAt, LocalDateTime.now()));

        ProcessProgressVO vo = loadProgressVO(docId, step);
        vo.setStatus("RUNNING");
        vo.setProgressPercent(0);
        vo.setMessage(message != null ? message : step.getDefaultMessage());
        pushProgressUpdate(docId, vo);
    }

    /**
     * 更新步骤进度。
     *
     * @param docId            文档ID
     * @param step             步骤枚举
     * @param progressPercent  进度百分比(0-100)
     * @param message          描述信息
     */
    public void updateStepProgress(Long docId, ProcessStep step, int progressPercent, String message) {
        progressMapper.update(null, new LambdaUpdateWrapper<DocumentProcessProgress>()
                .eq(DocumentProcessProgress::getDocId, docId)
                .eq(DocumentProcessProgress::getStep, step.getCode())
                .set(DocumentProcessProgress::getProgressPercent, progressPercent)
                .set(DocumentProcessProgress::getMessage, message));

        ProcessProgressVO vo = loadProgressVO(docId, step);
        vo.setProgressPercent(progressPercent);
        vo.setMessage(message);
        pushProgressUpdate(docId, vo);
    }

    /**
     * 完成指定步骤。
     *
     * @param docId   文档ID
     * @param step    步骤枚举
     * @param message 完成消息
     */
    public void completeStep(Long docId, ProcessStep step, String message) {
        progressMapper.update(null, new LambdaUpdateWrapper<DocumentProcessProgress>()
                .eq(DocumentProcessProgress::getDocId, docId)
                .eq(DocumentProcessProgress::getStep, step.getCode())
                .set(DocumentProcessProgress::getStatus, "COMPLETED")
                .set(DocumentProcessProgress::getProgressPercent, 100)
                .set(DocumentProcessProgress::getMessage, message != null ? message : step.getDefaultMessage())
                .set(DocumentProcessProgress::getCompletedAt, LocalDateTime.now()));

        ProcessProgressVO vo = loadProgressVO(docId, step);
        vo.setStatus("COMPLETED");
        vo.setProgressPercent(100);
        vo.setCompletedAt(LocalDateTime.now());
        vo.setMessage(message != null ? message : step.getDefaultMessage());
        pushProgressUpdate(docId, vo);
    }

    /**
     * 标记步骤失败。
     *
     * @param docId       文档ID
     * @param step        步骤枚举
     * @param errorDetail 错误详情
     */
    public void failStep(Long docId, ProcessStep step, String errorDetail) {
        progressMapper.update(null, new LambdaUpdateWrapper<DocumentProcessProgress>()
                .eq(DocumentProcessProgress::getDocId, docId)
                .eq(DocumentProcessProgress::getStep, step.getCode())
                .set(DocumentProcessProgress::getStatus, "FAILED")
                .set(DocumentProcessProgress::getProgressPercent, 0)
                .set(DocumentProcessProgress::getErrorDetail, errorDetail)
                .set(DocumentProcessProgress::getCompletedAt, LocalDateTime.now()));

        ProcessProgressVO vo = loadProgressVO(docId, step);
        vo.setStatus("FAILED");
        vo.setErrorDetail(errorDetail);
        vo.setCompletedAt(LocalDateTime.now());
        pushProgressUpdate(docId, vo);
    }

    /**
     * 标记文档处理完成（所有步骤完成）。
     */
    public void markCompleted(Long docId, Long kbId) {
        DocumentProcessProgress completed = DocumentProcessProgress.builder()
                .kbId(kbId)
                .docId(docId)
                .step(ProcessStep.COMPLETED.getCode())
                .stepOrder(ProcessStep.COMPLETED.getOrder())
                .status("COMPLETED")
                .progressPercent(100)
                .message("处理完成")
                .completedAt(LocalDateTime.now())
                .build();
        progressMapper.insert(completed);

        ProcessProgressVO vo = ProcessProgressVO.builder()
                .docId(docId)
                .step(ProcessStep.COMPLETED.getCode())
                .stepDisplayName(ProcessStep.COMPLETED.getDisplayName())
                .stepOrder(ProcessStep.COMPLETED.getOrder())
                .status("COMPLETED")
                .progressPercent(100)
                .message("处理完成")
                .completedAt(LocalDateTime.now())
                .build();
        pushProgressUpdate(docId, vo);
    }

    /**
     * 标记文档处理失败。
     */
    public void markFailed(Long docId, Long kbId, String reason) {
        String truncatedMsg = reason != null && reason.length() > 500 ? reason.substring(0, 500) + "..." : reason;
        String truncatedDetail = reason != null && reason.length() > 1000 ? reason.substring(0, 1000) + "..." : reason;

        DocumentProcessProgress failed = DocumentProcessProgress.builder()
                .kbId(kbId)
                .docId(docId)
                .step("FAILED")
                .stepOrder(99)
                .status("FAILED")
                .progressPercent(0)
                .message(truncatedMsg)
                .errorDetail(truncatedDetail)
                .completedAt(LocalDateTime.now())
                .build();
        progressMapper.insert(failed);

        ProcessProgressVO vo = ProcessProgressVO.builder()
                .docId(docId)
                .step("FAILED")
                .stepDisplayName("失败")
                .stepOrder(99)
                .status("FAILED")
                .progressPercent(0)
                .message(truncatedMsg)
                .errorDetail(truncatedDetail)
                .completedAt(LocalDateTime.now())
                .build();
        pushProgressUpdate(docId, vo);
    }

    /**
     * 订阅文档处理进度（SSE 端点）。
     *
     * <p>返回 Flux 流，推送 JSON 格式的进度事件。
     * 新订阅者会立即收到当前所有步骤的最新状态快照。
     *
     * @param docId 文档ID
     * @return SSE Flux 流
     */
    public Flux<String> subscribeProgress(Long docId) {
        return Flux.<String>create(sink -> {
            sinkRegistry.put(docId, sink);

            List<ProcessProgressVO> snapshot = loadAllProgressSnapshot(docId);
            if (snapshot != null && !snapshot.isEmpty()) {
                for (ProcessProgressVO vo : snapshot) {
                    sink.next(toJson(vo));
                }
            }

            sink.onCancel(() -> {
                sinkRegistry.remove(docId);
                log.debug("SSE订阅已取消: docId={}", docId);
            });
        }).doOnError(e -> {
            log.error("SSE推送异常: docId={}", docId, e);
            sinkRegistry.remove(docId);
        });
    }

    /**
     * 获取文档当前所有步骤的进度快照（用于断线恢复）。
     */
    public List<ProcessProgressVO> loadAllProgressSnapshot(Long docId) {
        List<DocumentProcessProgress> records = progressMapper.selectList(
                new LambdaQueryWrapper<DocumentProcessProgress>()
                        .eq(DocumentProcessProgress::getDocId, docId)
                        .orderByAsc(DocumentProcessProgress::getStepOrder));

        List<ProcessProgressVO> result = new ArrayList<>();
        for (DocumentProcessProgress record : records) {
            ProcessProgressVO vo = new ProcessProgressVO();
            BeanUtils.copyProperties(record, vo);
            ProcessStep step = ProcessStep.fromCode(record.getStep());
            if (step != null) {
                vo.setStepDisplayName(step.getDisplayName());
            }
            result.add(vo);
        }
        progressCache.put(docId, result);
        return result;
    }

    /**
     * 清理文档进度缓存。
     */
    public void cleanProgress(Long docId) {
        progressCache.remove(docId);
        sinkRegistry.remove(docId);
    }

    // ============ 内部方法 ============

    private ProcessProgressVO loadProgressVO(Long docId, ProcessStep step) {
        List<DocumentProcessProgress> records = progressMapper.selectList(
                new LambdaQueryWrapper<DocumentProcessProgress>()
                        .eq(DocumentProcessProgress::getDocId, docId)
                        .eq(DocumentProcessProgress::getStep, step.getCode())
                        .last("LIMIT 1"));

        ProcessProgressVO vo = new ProcessProgressVO();
        if (records != null && !records.isEmpty()) {
            BeanUtils.copyProperties(records.get(0), vo);
        }
        vo.setDocId(docId);
        vo.setStep(step.getCode());
        vo.setStepDisplayName(step.getDisplayName());
        vo.setStepOrder(step.getOrder());
        return vo;
    }

    private void pushProgressUpdate(Long docId, ProcessProgressVO vo) {
        String json = toJson(vo);

        List<ProcessProgressVO> cached = progressCache.computeIfAbsent(docId, k -> new ArrayList<>());
        cached.removeIf(p -> p.getStep() != null && p.getStep().equals(vo.getStep()));
        cached.add(vo);

        FluxSink<String> sink = sinkRegistry.get(docId);
        if (sink != null) {
            sink.next(json);
        }

        log.debug("进度推送: docId={}, step={}, status={}, progress={}",
                docId, vo.getStep(), vo.getStatus(), vo.getProgressPercent());
    }

    private String toJson(ProcessProgressVO vo) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"docId\":").append(vo.getDocId()).append(",");
        sb.append("\"step\":\"").append(vo.getStep()).append("\",");
        sb.append("\"stepDisplayName\":\"").append(vo.getStepDisplayName() != null ? vo.getStepDisplayName() : "").append("\",");
        sb.append("\"stepOrder\":").append(vo.getStepOrder() != null ? vo.getStepOrder() : 0).append(",");
        sb.append("\"status\":\"").append(vo.getStatus()).append("\",");
        sb.append("\"progressPercent\":").append(vo.getProgressPercent() != null ? vo.getProgressPercent() : 0).append(",");
        sb.append("\"message\":\"").append(escapeJson(vo.getMessage())).append("\"");
        if (vo.getErrorDetail() != null) {
            sb.append(",\"errorDetail\":\"").append(escapeJson(vo.getErrorDetail())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

package com.aegis.admin.service.observe;

import com.aegis.dal.mapper.monitor.AuditLogMapper;
import com.aegis.core.domain.monitor.AuditLog;
import com.aegis.core.enums.monitor.AuditLogType;
import com.aegis.core.enums.monitor.AuditResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志领域服务。
 *
 * <p>提供审计日志的多维度检索、导出与统计能力，支撑安全审计、合规检查与问题排查。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单次导出上限，防止无条件导出拖垮数据库与内存 */
    private static final int MAX_EXPORT_ROWS = 10000;

    /** 导出结果：CSV 字节内容与命中条数 */
    public record AuditExportResult(byte[] content, long count) {
    }

    /**
     * 分页查询审计日志。
     *
     * @param logType      日志类型
     * @param userId       操作人ID
     * @param result       操作结果
     * @param operation    操作类型（模糊匹配）
     * @param resourceName 资源名称（模糊匹配）
     * @param keyword      关键词（username/operation/resourceName 三列 OR 模糊匹配）
     * @param startTime    开始时间（yyyy-MM-dd HH:mm:ss）
     * @param endTime      结束时间（yyyy-MM-dd HH:mm:ss）
     * @param page         页码
     * @param size         每页条数
     * @return 审计日志分页结果
     */
    public Page<AuditLog> listLogs(String logType, Long userId, String result, String operation,
                                   String resourceName, String keyword, String sessionId, Long agentId,
                                   String startTime, String endTime,
                                   int page, int size) {
        Page<AuditLog> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(logType != null && !logType.isEmpty(), AuditLog::getLogType, logType)
                .eq(userId != null, AuditLog::getUserId, userId)
                .eq(result != null && !result.isEmpty(), AuditLog::getResult, result)
                .like(operation != null && !operation.isEmpty(), AuditLog::getOperation, operation)
                .like(resourceName != null && !resourceName.isEmpty(), AuditLog::getResourceName, resourceName)
                .eq(sessionId != null && !sessionId.isEmpty(), AuditLog::getSessionId, sessionId)
                .eq(agentId != null, AuditLog::getAgentId, agentId)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(AuditLog::getUsername, keyword)
                        .or().like(AuditLog::getOperation, keyword)
                        .or().like(AuditLog::getResourceName, keyword))
                .ge(startTime != null && !startTime.isEmpty(), AuditLog::getOccurTime, parseDateTime(startTime))
                .le(endTime != null && !endTime.isEmpty(), AuditLog::getOccurTime, parseDateTime(endTime))
                .orderByDesc(AuditLog::getOccurTime);
        return auditLogMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 导出审计日志为 CSV 文件（全维度过滤，CSV 含 username 列）。
     *
     * <p>按筛选条件查询真实数据（上限 {@value #MAX_EXPORT_ROWS} 条）生成 UTF-8 BOM CSV，
     * 并落一条 SECURITY 审计日志记录本次导出行为（含 operatorUsername）。
     *
     * @param tenantId     租户ID
     * @param userId       操作人ID
     * @param operatorUsername 操作人用户名（导出审计记录用）
     * @param logType      日志类型过滤
     * @param filterUserId 操作人ID过滤
     * @param result       操作结果过滤
     * @param operation    操作类型模糊
     * @param resourceName 资源名称模糊
     * @param keyword      关键词（username/operation/resourceName 三列 OR 模糊）
     * @param startTime    开始时间
     * @param endTime      结束时间
     * @return 导出结果（CSV 字节内容与命中条数）
     */
    public AuditExportResult exportLogs(Long tenantId, Long userId, String operatorUsername,
                                          String logType, Long filterUserId, String result,
                                          String operation, String resourceName, String keyword,
                                          String startTime, String endTime) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(logType != null && !logType.isEmpty(), AuditLog::getLogType, logType)
                .eq(filterUserId != null, AuditLog::getUserId, filterUserId)
                .eq(result != null && !result.isEmpty(), AuditLog::getResult, result)
                .like(operation != null && !operation.isEmpty(), AuditLog::getOperation, operation)
                .like(resourceName != null && !resourceName.isEmpty(), AuditLog::getResourceName, resourceName)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(AuditLog::getUsername, keyword)
                        .or().like(AuditLog::getOperation, keyword)
                        .or().like(AuditLog::getResourceName, keyword))
                .ge(startTime != null && !startTime.isEmpty(), AuditLog::getOccurTime, parseDateTime(startTime))
                .le(endTime != null && !endTime.isEmpty(), AuditLog::getOccurTime, parseDateTime(endTime))
                .orderByDesc(AuditLog::getOccurTime);

        // 总命中数（不受导出上限影响）
        long total = auditLogMapper.selectCount(wrapper);

        // 受上限约束的实际导出数据
        Page<AuditLog> pageObj = new Page<>(1, MAX_EXPORT_ROWS, false);
        List<AuditLog> logs = auditLogMapper.selectPage(pageObj, wrapper).getRecords();

        byte[] content = buildCsv(logs);

        // 记录导出审计日志（导出属安全敏感操作，归入 SECURITY，含 username）
        AuditLog exportLog = AuditLog.builder()
                .logType(AuditLogType.SECURITY)
                .userId(userId)
                .username(operatorUsername)
                .operation("EXPORT_AUDIT_LOG")
                .resourceType("AUDIT_LOG")
                .result(AuditResult.SUCCESS)
                .detail("导出审计日志，命中 " + total + " 条，实际导出 " + logs.size() + " 条（上限 " + MAX_EXPORT_ROWS + "）")
                .occurTime(LocalDateTime.now())
                .retentionDays(1095)
                .build();
        if (tenantId != null) {
            exportLog.setTenantId(tenantId);
        }
        auditLogMapper.insert(exportLog);

        return new AuditExportResult(content, total);
    }

    /**
     * 生成 UTF-8 BOM CSV 内容（Excel 可直接打开）。
     *
     * <p>遵循 RFC 4180：含逗号/引号/换行的字段以双引号包裹，内部引号翻倍；
     * 对以 =/+/-/@ 开头的单元格追加单引号前缀，防止公式注入。
     */
    private byte[] buildCsv(List<AuditLog> logs) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(8192)) {
            // UTF-8 BOM，保证 Excel 识别中文
            bos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            Writer w = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
            w.write("id,logType,userId,username,operation,resourceType,resourceName,result,ip,traceId,sessionId,agentId,occurTime,retentionDays,detail\n");
            DateTimeFormatter fmt = DATE_TIME_FORMATTER;
            for (AuditLog log : logs) {
                w.write(escape(log.getId() != null ? String.valueOf(log.getId()) : ""));
                w.write(',');
                w.write(escape(log.getLogType() != null ? log.getLogType().name() : ""));
                w.write(',');
                w.write(escape(log.getUserId() != null ? String.valueOf(log.getUserId()) : ""));
                w.write(',');
                w.write(escape(log.getUsername()));
                w.write(',');
                w.write(escape(log.getOperation()));
                w.write(',');
                w.write(escape(log.getResourceType()));
                w.write(',');
                w.write(escape(log.getResourceName()));
                w.write(',');
                w.write(escape(log.getResult() != null ? log.getResult().name() : ""));
                w.write(',');
                w.write(escape(log.getIp()));
                w.write(',');
                w.write(escape(log.getTraceId()));
                w.write(',');
                w.write(escape(log.getSessionId()));
                w.write(',');
                w.write(escape(log.getAgentId() != null ? String.valueOf(log.getAgentId()) : ""));
                w.write(',');
                w.write(escape(log.getOccurTime() != null ? log.getOccurTime().format(fmt) : ""));
                w.write(',');
                w.write(escape(log.getRetentionDays() != null ? String.valueOf(log.getRetentionDays()) : ""));
                w.write(',');
                w.write(escape(log.getDetail()));
                w.write('\n');
            }
            w.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成审计导出 CSV 失败", e);
        }
    }

    /**
     * CSV 字段转义：RFC 4180 + 公式注入防护。
     */
    private String escape(String field) {
        if (field == null || field.isEmpty()) {
            return "";
        }
        String value = field;
        // 公式注入防护：= / + / - / @ 开头的单元格前置单引号
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * 审计日志统计（按日志类型聚合 + 安全事件子计数）。
     *
     * <p>返回各 logType 记录数、total，以及 blockCount/decisionCount/askCount 子计数，
     * 合并原 SecurityAuditController 的安全事件统计能力。
     *
     * @return 各日志类型的记录数 + 安全事件子计数
     */
    public Map<String, Object> stats(String startTime, String endTime) {
        Map<String, Object> stats = new HashMap<>();
        QueryWrapper<AuditLog> wrapper = new QueryWrapper<>();
        wrapper.select("log_type as logType, COUNT(*) as cnt");
        wrapper.groupBy("log_type");
        if (startTime != null && !startTime.isEmpty()) {
            wrapper.ge("occur_time", parseDateTime(startTime));
        }
        if (endTime != null && !endTime.isEmpty()) {
            wrapper.le("occur_time", parseDateTime(endTime));
        }
        List<Map<String, Object>> results = auditLogMapper.selectMaps(wrapper);

        long total = 0;
        // 初始化所有类型为0
        for (AuditLogType type : AuditLogType.values()) {
            stats.put(type.name(), 0L);
        }
        // 填充查询结果
        for (Map<String, Object> row : results) {
            String logType = String.valueOf(row.get("logType"));
            long count = ((Number) row.get("cnt")).longValue();
            stats.put(logType, count);
            total += count;
        }
        stats.put("total", total);

        // 安全事件子计数（含时间范围联动）
        LambdaQueryWrapper<AuditLog> blockWrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getLogType, AuditLogType.SECURITY)
                .eq(AuditLog::getOperation, "SECURITY_BLOCK");
        applyTimeRange(blockWrapper, startTime, endTime);
        stats.put("blockCount", auditLogMapper.selectCount(blockWrapper));

        LambdaQueryWrapper<AuditLog> decisionWrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getLogType, AuditLogType.POLICY_DECISION);
        applyTimeRange(decisionWrapper, startTime, endTime);
        stats.put("decisionCount", auditLogMapper.selectCount(decisionWrapper));

        LambdaQueryWrapper<AuditLog> askWrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getLogType, AuditLogType.SECURITY)
                .eq(AuditLog::getOperation, "SECURITY_ASK");
        applyTimeRange(askWrapper, startTime, endTime);
        stats.put("askCount", auditLogMapper.selectCount(askWrapper));

        return stats;
    }

    /** 给 LambdaQueryWrapper 追加 occurTime 时间范围过滤 */
    private void applyTimeRange(LambdaQueryWrapper<AuditLog> wrapper, String startTime, String endTime) {
        if (startTime != null && !startTime.isEmpty()) {
            wrapper.ge(AuditLog::getOccurTime, parseDateTime(startTime));
        }
        if (endTime != null && !endTime.isEmpty()) {
            wrapper.le(AuditLog::getOccurTime, parseDateTime(endTime));
        }
    }

    /**
     * 解析日期时间字符串。
     *
     * @param dateTime 日期时间字符串（yyyy-MM-dd HH:mm:ss）
     * @return LocalDateTime 对象
     */
    private LocalDateTime parseDateTime(String dateTime) {
        try {
            return LocalDateTime.parse(dateTime, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("解析日期时间失败: {}", dateTime, e);
            return null;
        }
    }
}

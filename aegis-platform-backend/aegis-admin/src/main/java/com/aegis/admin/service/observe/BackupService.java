package com.aegis.admin.service.observe;

import com.aegis.dal.mapper.monitor.BackupRecordMapper;
import com.aegis.core.domain.monitor.BackupRecord;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份管理服务。
 *
 * <p>提供数据库备份的执行与历史查询能力。备份通过 mysqldump 命令执行，
 * 当 mysqldump 不可用时记录失败状态，确保备份操作可追溯。</p>
 *
 * <p>备份目录与数据库连接参数通过 {@code aegis.ha.backup.*} 配置项注入：</p>
 * <ul>
 *     <li>{@code aegis.ha.backup.dir}：备份文件目录（默认 /data/backups/aegis）</li>
 *     <li>{@code aegis.ha.backup.mysql-host/port/user/password/database}：备份目标库连接</li>
 *     <li>{@code aegis.ha.backup.mysqldump-path}：mysqldump 可执行文件路径（默认走 PATH）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Service
@Slf4j
public class BackupService {

    private final BackupRecordMapper backupRecordMapper;

    @Value("${aegis.ha.backup.dir:/data/backups/aegis}")
    private String backupDir;

    @Value("${aegis.ha.backup.mysql-host:127.0.0.1}")
    private String mysqlHost;

    @Value("${aegis.ha.backup.mysql-port:3306}")
    private int mysqlPort;

    @Value("${aegis.ha.backup.mysql-user:aegis}")
    private String mysqlUser;

    @Value("${aegis.ha.backup.mysql-password:}")
    private String mysqlPassword;

    @Value("${aegis.ha.backup.mysql-database:aegis}")
    private String mysqlDatabase;

    @Value("${aegis.ha.backup.mysqldump-path:mysqldump}")
    private String mysqldumpPath;

    public BackupService(BackupRecordMapper backupRecordMapper) {
        this.backupRecordMapper = backupRecordMapper;
    }

    /**
     * 分页查询备份历史。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 备份记录分页结果
     */
    public Page<BackupRecord> list(int page, int size) {
        Page<BackupRecord> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<BackupRecord> wrapper = new LambdaQueryWrapper<BackupRecord>()
                .orderByDesc(BackupRecord::getOccurTime);
        return backupRecordMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 手动触发备份。
     *
     * <p>通过 mysqldump 命令执行全量备份，备份文件存储到 {@code {备份目录}/{backupId}.sql}。
     * 如果 mysqldump 不可用，则记录 status=FAILED。</p>
     *
     * @return 备份记录
     */
    public BackupRecord execute() {
        String backupId = "BK-" + LocalDate.now() + "-" + System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();

        BackupRecord record = BackupRecord.builder()
                .backupId(backupId)
                .backupType("FULL")
                .status("RUNNING")
                .occurTime(now)
                .build();

        backupRecordMapper.insert(record);

        long startTime = System.currentTimeMillis();
        try {
            // 检查 mysqldump 是否可用
            ProcessBuilder checkPb = new ProcessBuilder(mysqldumpPath, "--version");
            checkPb.redirectErrorStream(true);
            Process checkProc = checkPb.start();
            int checkCode = checkProc.waitFor();
            if (checkCode != 0) {
                record.setStatus("FAILED");
                record.setLocation("mysqldump not available: " + mysqldumpPath);
                backupRecordMapper.updateById(record);
                log.warn("mysqldump 不可用，备份失败: backupId={}, path={}", backupId, mysqldumpPath);
                return record;
            }

            // 创建备份目录
            File backupDirFile = new File(backupDir);
            if (!backupDirFile.exists() && !backupDirFile.mkdirs()) {
                record.setStatus("FAILED");
                record.setLocation("cannot create backup dir: " + backupDir);
                backupRecordMapper.updateById(record);
                log.warn("备份目录创建失败: backupId={}, dir={}", backupId, backupDir);
                return record;
            }

            String backupFile = backupDir + "/" + backupId + ".sql";
            List<String> command = new ArrayList<>();
            command.add(mysqldumpPath);
            command.add("-h" + mysqlHost);
            command.add("-P" + mysqlPort);
            command.add("-u" + mysqlUser);
            if (mysqlPassword != null && !mysqlPassword.isEmpty()) {
                command.add("-p" + mysqlPassword);
            }
            command.add(mysqlDatabase);
            command.add("-r");
            command.add(backupFile);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            long durationSec = (System.currentTimeMillis() - startTime) / 1000;

            if (exitCode == 0) {
                File file = new File(backupFile);
                record.setStatus("SUCCESS");
                record.setLocation(backupFile);
                record.setSizeBytes(file.exists() ? file.length() : 0L);
                record.setDurationSec((int) durationSec);
            } else {
                record.setStatus("FAILED");
                record.setLocation(backupFile);
                record.setDurationSec((int) durationSec);
                log.warn("mysqldump 执行失败: exitCode={}, backupId={}", exitCode, backupId);
            }
        } catch (Exception e) {
            long durationSec = (System.currentTimeMillis() - startTime) / 1000;
            record.setStatus("FAILED");
            record.setDurationSec((int) durationSec);
            record.setLocation(e.getMessage());
            log.error("备份执行异常: backupId={}", backupId, e);
        }

        backupRecordMapper.updateById(record);
        return record;
    }
}

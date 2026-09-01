package com.aegis.core.domain.monitor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import com.aegis.core.base.TenantEntity;

/**
 * 备份记录实体。
 *
 * <p>备份记录（BackupRecord）记录平台数据库备份的执行情况，包括备份类型、大小、
 * 耗时、状态与存储位置，支撑备份策略管理与恢复验证。</p>
 *
 * <h3>备份类型</h3>
 * <ul>
 *     <li>FULL：全量备份，备份数据库全部数据</li>
 *     <li>INCREMENTAL：增量备份，仅备份自上次备份以来的变更</li>
 * </ul>
 *
 * <h3>设计说明</h3>
 * <p>不继承 TenantEntity，手动维护 tenantId 字段，因为备份记录属于系统级记录，
 * 需要跨租户可见。</p>
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mon_backup_record")
public class BackupRecord {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 备份ID，格式：BK-{date}-{序号} */
    private String backupId;

    /** 备份类型：FULL（全量）/ INCREMENTAL（增量） */
    private String backupType;

    /** 备份大小，单位字节 */
    private Long sizeBytes;

    /** 耗时，单位秒 */
    private Integer durationSec;

    /** 状态：SUCCESS / RUNNING / FAILED */
    private String status;

    /** 存储位置，备份文件路径 */
    private String location;

    /** 执行时间 */
    private LocalDateTime occurTime;

    /** 租户ID，系统级记录手动维护 */
    private Long tenantId;
}

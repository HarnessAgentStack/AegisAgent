package com.aegis.core.domain.workspace;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 智能体工作区物化指纹实体。
 * 对应 agent_workspace_material 表，记录每次物化的指纹快照，用于增量检测。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workspace_material")
public class AgentWorkspaceMaterial extends TenantEntity {
    /** 智能体ID */
    private Long agentId;
    /** 智能体版本（物化时的版本号，默认 0） */
    @Builder.Default
    private Integer agentVersion = 0;
    /** 用户ID（通用智能体按用户隔离，应用/系统智能体为0） */
    private Long userId;
    /** 隔离作用域：USER / AGENT / GLOBAL */
    private String isolationScope;
    /** 工作区路径 */
    private String workspacePath;
    /** 物化指纹（绑定资源版本号的 hash） */
    private String materialFingerprint;
    /** 绑定快照（JSON，记录当时的绑定列表） */
    private String bindingSnapshot;
    /** 最后物化时间 */
    private LocalDateTime lastMaterializedAt;
}

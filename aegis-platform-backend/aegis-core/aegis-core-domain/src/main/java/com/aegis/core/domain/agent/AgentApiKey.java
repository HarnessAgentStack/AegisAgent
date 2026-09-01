package com.aegis.core.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_api_key")
public class AgentApiKey {

    private Long id;

    /** 租户ID，用于多租户隔离 */
    private Long tenantId;

    private Long agentId;

    private Long apiId;

    private String apiKeyHash;

    private String keyLabel;

    /** ACTIVE / REVOKED / EXPIRED */
    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private Long rotateFrom;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApiVersionInfo implements Serializable {

    private String version;

    private String apiName;

    private String apiPath;

    private String status;

    private LocalDateTime lastTestedAt;

    private Integer concurrentLimit;

    private Integer rateLimit;
}
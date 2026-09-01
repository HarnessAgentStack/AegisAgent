package com.aegis.core.dto.resource;

import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 工具视图对象（简化版，用于 MCP 审核详情展示）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String toolCode;
    private String toolName;
    private String description;
    private ToolType toolType;
    private ToolSourceType sourceType;
    private Boolean readOnly;
    private String inputSchema;
    private String outputSchema;
    private SecurityLevel securityLevel;
    private CommonStatus status;
}

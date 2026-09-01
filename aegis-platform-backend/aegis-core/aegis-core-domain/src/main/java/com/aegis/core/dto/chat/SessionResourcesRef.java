package com.aegis.core.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 会话级资源引用。
 * 允许用户在对话中临时选择可用的知识库和MCP服务，仅本次会话生效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResourcesRef implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 选择的知识库ID列表 */
    private List<Long> kbIds;

    /** 选择的MCP服务ID列表 */
    private List<Long> mcpIds;
}

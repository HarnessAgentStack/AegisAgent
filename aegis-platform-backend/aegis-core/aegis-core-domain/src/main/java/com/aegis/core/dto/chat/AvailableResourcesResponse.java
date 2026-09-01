package com.aegis.core.dto.chat;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可用资源响应。
 * 返回当前用户在指定智能体下可用的知识库和MCP服务列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableResourcesResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 可用知识库列表 */
    private List<KbResourceItem> kbs;

    /** 可用MCP服务列表 */
    private List<McpResourceItem> mcps;

    /** 知识库总数 */
    @Builder.Default
    private int totalKbCount = 0;

    /** MCP服务总数 */
    @Builder.Default
    private int totalMcpCount = 0;

    /**
     * 校验时发现不可引用的知识库 ID 列表（validate 接口返回，
     * 提示用户哪些引用已失效，而非静默丢弃）。
     */
    @Builder.Default
    private List<Long> invalidKbIds = List.of();

    /** 校验时发现不可引用的 MCP 服务 ID 列表 */
    @Builder.Default
    private List<Long> invalidMcpIds = List.of();

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KbResourceItem implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 知识库ID（使用字符串避免JavaScript精度丢失） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        /** 知识库名称 */
        private String name;

        /** 知识库描述 */
        private String description;

        /** 安全等级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET） */
        private String securityLevel;

        /** 是否已订阅 */
        private boolean subscribed;

        /** 文档数量 */
        @Builder.Default
        private int documentCount = 0;

        /** 创建者 */
        private String creator;

        /** 创建时间 */
        private LocalDateTime createTime;

        /** 标签/分类 */
        private List<String> tags;

        /**
         * 当前智能体治理档位下是否允许引用该知识库。
         *
         * <p>false 表示安全等级高于档位（KB_RETRIEVE 评估 ASK/REJECT），
         * 不可选时携带 {@link #blockReason} 说明原因。
         */
        @Builder.Default
        private boolean selectable = true;

        /** 不可选原因（档位不匹配说明，selectable=false 时有值） */
        private String blockReason;

        /**
         * 知识库生命周期状态（DRAFT/REVIEWING/PUBLISHED/ARCHIVED/REJECTED）。
         *
         * <p>非 PUBLISHED 的自建库仅创建者可见可引用。
         */
        private String lifeStatus;

        /** 是否当前用户创建（自建库标识） */
        @Builder.Default
        private boolean owned = false;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpResourceItem implements Serializable {
        private static final long serialVersionUID = 1L;

        /** MCP服务ID（使用字符串避免JavaScript精度丢失） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        /** MCP服务名称 */
        private String name;

        /** MCP服务描述 */
        private String description;

        /** 安全等级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET） */
        private String securityLevel;

        /** 工具数量 */
        @Builder.Default
        private int toolCount = 0;

        /** 是否已订阅 */
        private boolean subscribed;

        /** 订阅数 */
        @Builder.Default
        private int subsCount = 0;

        /** 创建者 */
        private String creator;

        /** 创建时间 */
        private LocalDateTime createTime;

        /** 标签/分类 */
        private List<String> tags;
    }
}

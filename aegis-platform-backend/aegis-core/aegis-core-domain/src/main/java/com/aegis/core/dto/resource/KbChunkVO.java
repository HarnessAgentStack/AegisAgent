package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;

/**
 * 知识库切片预览VO。
 *
 *  @author wang.zhen  
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbChunkVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long docId;

    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private Integer charCount;
    private String metadata;
}

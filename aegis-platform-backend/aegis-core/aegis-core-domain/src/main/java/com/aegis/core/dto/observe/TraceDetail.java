package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 链路详情。
 *
 * <p>包含链路主记录与完整的 Span 树结构，用于链路详情页展示。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 链路主记录 */
    private TraceRecord trace;

    /** Span 记录列表 */
    private List<SpanRecord> spans;
}
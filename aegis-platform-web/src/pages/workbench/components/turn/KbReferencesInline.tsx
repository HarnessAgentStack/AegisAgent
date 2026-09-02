/**
 * @file 知识库紧凑引用表
 * @description 回答末尾的论文式引用：单条一行、hover Tooltip 显示 snippet、
 *              可选点击展开 inline 片段。总高度 ≤ 3 行（前 5 条），超过折叠。
 *              取代旧 AssistantTurn 中占 8~15 行的大卡片块。
 *
 * @author Aegis
 * @since 4.1.0
 */
import React, { useState } from 'react';
import { Tooltip, Button } from 'antd';
import { KbReference } from '@/types/session';
import { formatPercent } from '@/utils/format';

interface Props {
  references: KbReference[];
  /** 折叠阈值：超过此数折叠 */
  truncate?: number;
}

export const KbReferencesInline: React.FC<Props> = ({ references, truncate = 5 }) => {
  const [showAll, setShowAll] = useState(false);
  const [expandedIdx, setExpandedIdx] = useState<Set<number>>(new Set());

  if (!references || references.length === 0) return null;

  const shown = showAll ? references : references.slice(0, truncate);
  const hasMore = references.length > truncate;

  return (
    <div
      style={{
        marginTop: 8,
        padding: '6px 10px',
        background: '#fafbfc',
        borderTop: '1px solid #e8e8e8',
        borderRadius: 6,
        fontSize: 12,
        color: '#6b7280',
      }}
    >
      {/* 标题行 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        <span style={{ color: '#1890ff', fontSize: 13 }}>📎</span>
        <span style={{ fontWeight: 600, color: '#374151' }}>References ({references.length})</span>
      </div>

      {/* 引用列表 */}
      <ol style={{ margin: 0, paddingLeft: 18, listStyle: 'decimal' }}>
        {shown.map((ref, i) => {
          const expanded = expandedIdx.has(i);
          const label = ref.documentName ?? ref.knowledgeBaseName ?? `文档 ${i + 1}`;
          const score = ref.score !== undefined ? `${formatPercent(ref.score, 1)}` : '';

          return (
            <li key={ref.id ?? i} style={{ marginBottom: expanded ? 6 : 2, lineHeight: 1.4 }} id={`ref-${i}`}>
              <Tooltip
                title={
                  ref.snippet && ref.snippet.length > 120
                    ? ref.snippet.slice(0, 120) + '...'
                    : ref.snippet || (ref.knowledgeBaseName ? `知识库: ${ref.knowledgeBaseName}` : '')
                }
                mouseEnterDelay={0.4}
                placement="top"
              >
                <span
                  style={{ color: '#1890ff', cursor: 'help', fontWeight: 500 }}
                  onClick={() => {
                    if (!ref.snippet) return;
                    setExpandedIdx((prev) => {
                      const next = new Set(prev);
                      if (next.has(i)) next.delete(i);
                      else next.add(i);
                      return next;
                    });
                  }}
                >
                  {label}
                </span>
              </Tooltip>
              {score && (
                <span style={{ color: '#9ca3af', marginLeft: 4 }}>({score})</span>
              )}
              {ref.sourceUrl && (
                <a
                  href={ref.sourceUrl}
                  target="_blank"
                  rel="noreferrer"
                  style={{ color: '#1890ff', marginLeft: 4, fontSize: 11 }}
                  onClick={(e) => e.stopPropagation()}
                >
                  [link]
                </a>
              )}

              {/* inline snippet 展开 */}
              {expanded && ref.snippet && (
                <div
                  style={{
                    marginTop: 3,
                    padding: '4px 6px',
                    background: '#fff',
                    border: '1px solid #e6f0ff',
                    borderRadius: 4,
                    fontSize: 11,
                    color: '#4b5563',
                    whiteSpace: 'pre-wrap',
                    lineHeight: 1.5,
                  }}
                >
                  {ref.snippet}
                </div>
              )}
            </li>
          );
        })}
      </ol>

      {/* 展开更多 */}
      {hasMore && (
        <Button
          type="link"
          size="small"
          style={{ fontSize: 11, padding: 0, height: 'auto', marginTop: 2 }}
          onClick={() => { setShowAll((v) => !v); setExpandedIdx(new Set()); }}
        >
          {showAll ? `收起 (显示前 ${truncate} 条)` : `展开全部 ${references.length} 条`}
        </Button>
      )}
    </div>
  );
};

export default KbReferencesInline;

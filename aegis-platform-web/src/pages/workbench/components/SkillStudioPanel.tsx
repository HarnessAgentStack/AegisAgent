/**
 * @file SkillStudioPanel - 工作台内实时技能草稿预览面板
 * @description 技能创建模式下的右侧面板，展示AI生成的技能结构和文件目录。
 *              仅保留核心操作：调试、保存。其他操作在技能详情页完成。
 *              支持文件树展示（SKILL.md + 依赖文件）。
 * @author aegis
 * @since 3.0.0
 */
import React, { useMemo, useState } from 'react';
import {
  Button,
  Input,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  BugOutlined,
  CheckCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  FileOutlined,
  FolderOutlined,
  SaveOutlined,
  ThunderboltOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { SkillType } from '@/pages/resource/skill/constants';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;

/** 技能草稿数据（前端本地状态） */
export interface SkillDraft {
  skillName: string;
  skillCode: string;
  skillType: SkillType;
  category: string;
  description: string;
  instructions: string;
  securityLevel: string;
  tags: string[];
  bindingTools: string;
  /** 输入参数定义（JSON Schema 字符串） */
  inputs?: string;
  /** 输出参数定义（JSON Schema 字符串） */
  outputs?: string;
}

/** 技能文件目录项 */
export interface SkillFileItem {
  name: string;
  type: 'file' | 'folder';
  path: string;
  content?: string;
  children?: SkillFileItem[];
}

/** 绑定工具分组 */
interface ToolBindingGroup {
  mcpId: number;
  mcpName: string;
  mcpCode?: string;
  tools: Array<{
    toolCode: string;
    toolName?: string;
    selected: boolean;
  }>;
}

export interface SkillCreatorStageState {
  phase: string;
  description: string;
  progress: number;
  ts: number;
}

export interface SkillStudioPanelProps {
  draftSkillId: string | null;
  draft: SkillDraft;
  files?: SkillFileItem[];
  stage?: SkillCreatorStageState | null;
  debugResult?: { success: boolean; message?: string; ts: number } | null;
  onDebug: () => void;
  onSave: () => void;
  onAIDirective: (message: string) => void;
  onClose: () => void;
  onSubmitted?: (skillId: string) => void;
  onTagsChange?: (tags: string[]) => void;
  streaming?: boolean;
}

export const SkillStudioPanel: React.FC<SkillStudioPanelProps> = ({
  draftSkillId,
  draft,
  files = [],
  stage,
  debugResult,
  onDebug,
  onSave,
  onClose,
  onSubmitted,
  onTagsChange,
  streaming,
}) => {
  const [expandedFiles, setExpandedFiles] = useState(true);

  const bindingGroups = useMemo<ToolBindingGroup[]>(() => {
    if (!draft.bindingTools) return [];
    const data = safeJsonParse<Record<string, unknown>>(draft.bindingTools);
    if (data && typeof data === 'object' && !Array.isArray(data)) {
      return Object.entries(data).map(([mcpName, tools]) => ({
        mcpId: 0,
        mcpName,
        tools: Array.isArray(tools)
          ? tools.map((t: string | Record<string, unknown>) => ({
              toolCode: typeof t === 'string' ? t : String((t as Record<string, unknown>).toolCode ?? ''),
              toolName: typeof t === 'string' ? undefined : String((t as Record<string, unknown>).toolName ?? ''),
              selected: true,
            }))
          : [],
      }));
    }
    return [];
  }, [draft.bindingTools]);

  const selectedToolCount = useMemo(
    () => bindingGroups.reduce((sum, g) => sum + g.tools.filter(t => t.selected).length, 0),
    [bindingGroups]
  );

  const detectedVariables = useMemo(() => {
    const regex = /\{(\w+)\}/g;
    const found = new Set<string>();
    let match: RegExpExecArray | null;
    while ((match = regex.exec(draft.instructions)) !== null) {
      found.add(match[1]);
    }
    return Array.from(found);
  }, [draft.instructions]);

  const fileCount = useMemo(() => {
    const countFiles = (items: SkillFileItem[]): number => {
      return items.reduce((sum, item) => {
        if (item.type === 'file') return sum + 1;
        if (item.children) return sum + countFiles(item.children);
        return sum;
      }, 0);
    };
    return countFiles(files);
  }, [files]);

  const skillReady = draftSkillId != null && !!draft.skillName;

  const renderHeader = () => (
    <div style={{
      padding: '10px 14px',
      borderBottom: '1px solid #e8e8e8',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      background: '#fff',
      flexShrink: 0,
    }}>
      <Space size={8}>
        <div style={{
          width: 26, height: 26, borderRadius: 6,
          background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <ThunderboltOutlined style={{ fontSize: 13, color: '#fff' }} />
        </div>
        <div>
          <div style={{ fontWeight: 600, fontSize: 12, color: '#1f2937' }}>技能草稿</div>
          {draftSkillId && (
            <div style={{ fontSize: 10, color: '#9ca3af' }}>ID: #{draftSkillId}</div>
          )}
        </div>
      </Space>
      <Button
        type="text"
        size="small"
        icon={<CloseOutlined style={{ fontSize: 12 }} />}
        onClick={onClose}
        title="关闭技能创建面板"
      />
    </div>
  );

  const renderStageProgress = () => {
    if (!stage) return null;
    const isComplete = stage.progress >= 100;
    return (
      <div style={{
        padding: '8px 14px',
        background: isComplete ? '#f6ffed' : '#fffbe6',
        borderBottom: '1px solid #f0f0f0',
        flexShrink: 0,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
          {isComplete
            ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
            : <SaveOutlined style={{ color: '#faad14', fontSize: 12 }} spin={!isComplete} />
          }
          <span style={{ color: '#374151', flex: 1, fontSize: 12, fontWeight: 500 }}>
            {stage.description || stage.phase}
          </span>
          <span style={{ color: '#9ca3af', fontSize: 11 }}>{stage.progress}%</span>
        </div>
        <div style={{ height: 3, background: '#f0f0f0', borderRadius: 2, overflow: 'hidden' }}>
          <div style={{
            height: '100%',
            width: `${stage.progress}%`,
            background: isComplete ? '#52c41a' : '#faad14',
            transition: 'width 0.3s',
          }} />
        </div>
      </div>
    );
  };

  const renderSkillInfo = () => (
    <div style={{
      padding: 12,
      background: 'linear-gradient(135deg, #f8f9ff 0%, #f0f4ff 100%)',
      border: '1px solid #e0e7ff',
      borderRadius: 8,
      marginBottom: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: '#1f2937' }}>
          {draft.skillName || '未命名技能'}
        </span>
        {draft.skillType && (
          <Tag color="blue" style={{ marginLeft: 'auto', fontSize: 11 }}>
            {draft.skillType === 'COMPOSITE' ? '组合' : '原子'}
          </Tag>
        )}
      </div>
      <div style={{ fontSize: 11, color: '#6b7280', fontFamily: 'monospace', marginBottom: 6 }}>
        @{draft.skillCode || 'SKILL_CODE'}
      </div>
      {draft.description && (
        <div style={{
          fontSize: 12, color: '#6b7280', lineHeight: '18px',
          overflow: 'hidden', textOverflow: 'ellipsis',
          display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
        }}>
          {draft.description}
        </div>
      )}
      {onTagsChange && (
        <div style={{ marginTop: 8 }}>
          <span style={{ fontSize: 12, color: '#999' }}>标签</span>
          <div style={{ marginTop: 4 }}>
            {(draft.tags ?? []).map((t) => (
              <Tag
                key={t}
                closable
                onClose={() => onTagsChange((draft.tags ?? []).filter((x) => x !== t))}
              >
                {t}
              </Tag>
            ))}
            <Input
              size="small"
              style={{ width: 100 }}
              placeholder="添加标签"
              onPressEnter={(e) => {
                const v = (e.target as HTMLInputElement).value.trim();
                if (v && !draft.tags?.includes(v)) onTagsChange([...(draft.tags ?? []), v]);
                (e.target as HTMLInputElement).value = '';
              }}
            />
          </div>
        </div>
      )}
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 8 }}>
        {draft.category && <Tag color="purple" style={{ fontSize: 11 }}>{draft.category}</Tag>}
        {draft.securityLevel && <Tag color="orange" style={{ fontSize: 11 }}>{draft.securityLevel}</Tag>}
        {selectedToolCount > 0 && <Tag color="green" style={{ fontSize: 11 }}>{selectedToolCount} 工具</Tag>}
      </div>
    </div>
  );

  const renderInstructions = () => {
    if (!draft.instructions) return null;
    return (
      <div style={{ marginBottom: 12 }}>
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 12,
          fontWeight: 600,
          color: '#374151',
          marginBottom: 6,
        }}>
          <CodeOutlined style={{ fontSize: 12 }} />
          <span>指令</span>
        </div>
        <div style={{
          padding: 10,
          background: '#f9fafb',
          borderRadius: 6,
          fontFamily: 'Consolas, "Courier New", monospace',
          fontSize: 12,
          lineHeight: '18px',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          maxHeight: 120,
          overflowY: 'auto',
          border: '1px solid #f0f0f0',
        }}>
          {draft.instructions}
        </div>
        {detectedVariables.length > 0 && (
          <div style={{ marginTop: 6, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
            {detectedVariables.map((v) => (
              <Tag key={v} color="processing" style={{ fontSize: 10 }}>
                {'{'}{v}{'}'}
              </Tag>
            ))}
          </div>
        )}
      </div>
    );
  };

  const renderBindingTools = () => {
    if (bindingGroups.length === 0) return null;
    return (
      <div style={{ marginBottom: 12 }}>
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 12,
          fontWeight: 600,
          color: '#374151',
          marginBottom: 6,
        }}>
          <ToolOutlined style={{ fontSize: 12 }} />
          <span>绑定工具</span>
        </div>
        {bindingGroups.map((group) => (
          <div
            key={group.mcpName}
            style={{
              border: '1px solid #f0f0f0',
              borderRadius: 6,
              padding: '8px 10px',
              background: '#fafafa',
              marginBottom: 6,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
              <span style={{ color: '#1677ff' }}>🔌</span>
              <Text strong style={{ fontSize: 12 }}>{group.mcpName}</Text>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {group.tools.filter(t => t.selected).map((tool) => (
                <Tag key={tool.toolCode} style={{ fontFamily: 'monospace', fontSize: 11 }}>
                  {tool.toolName || tool.toolCode}
                </Tag>
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  };

  const renderFileTree = (items: SkillFileItem[], level = 0): React.ReactNode => {
    return items.map((item) => (
      <div key={item.path}>
        <div
          style={{
            padding: '4px 8px',
            paddingLeft: `${8 + level * 14}px`,
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            cursor: item.type === 'file' ? 'pointer' : 'default',
            fontSize: 12,
            borderRadius: 4,
            transition: 'background 0.15s',
          }}
          onMouseEnter={(e) => { if (item.type === 'file') (e.currentTarget as HTMLElement).style.background = '#e6f4ff'; }}
          onMouseLeave={(e) => { if (item.type === 'file') (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
          onClick={() => {
            if (item.type === 'file' && item.content) {
              Modal.info({
                title: item.name,
                width: 560,
                content: (
                  <pre style={{
                    background: '#f5f5f5',
                    padding: 14,
                    borderRadius: 6,
                    maxHeight: 400,
                    overflow: 'auto',
                    fontSize: 12,
                    fontFamily: 'monospace',
                    whiteSpace: 'pre-wrap',
                    lineHeight: '18px',
                  }}>
                    {item.content}
                  </pre>
                ),
              });
            }
          }}
        >
          {item.type === 'folder'
            ? <FolderOutlined style={{ color: '#faad14', fontSize: 13 }} />
            : item.name === 'SKILL.md'
              ? <FileOutlined style={{ color: '#667eea', fontSize: 13 }} />
              : <FileOutlined style={{ color: '#1677ff', fontSize: 13 }} />
          }
          <span style={{
            fontWeight: item.type === 'folder' || item.name === 'SKILL.md' ? 600 : 400,
            color: '#1f2937',
          }}>
            {item.name}
          </span>
          {item.type === 'file' && (
            <span style={{ fontSize: 10, color: '#9ca3af', marginLeft: 'auto', fontFamily: 'monospace' }}>
              {item.path}
            </span>
          )}
        </div>
        {item.children && item.children.length > 0 && renderFileTree(item.children, level + 1)}
      </div>
    ));
  };

  const renderFileStructure = () => {
    if (files.length === 0 && !draftSkillId) return null;
    return (
      <div style={{ marginBottom: 12 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 6,
          }}
        >
          <div style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 12,
            fontWeight: 600,
            color: '#374151',
          }}>
            <FileOutlined style={{ fontSize: 12 }} />
            <span>技能文件</span>
            {fileCount > 0 && (
              <Tag color="default" style={{ fontSize: 10, margin: 0 }}>
                {fileCount}
              </Tag>
            )}
          </div>
          {files.length > 0 && (
            <Button
              size="small"
              type="text"
              onClick={() => setExpandedFiles(!expandedFiles)}
              style={{ fontSize: 11, color: '#6b7280' }}
            >
              {expandedFiles ? '收起' : '展开'}
            </Button>
          )}
        </div>
        <div style={{
          border: '1px solid #e8e8e8',
          borderRadius: 6,
          background: '#fafbfc',
          padding: 6,
        }}>
          {files.length > 0 ? (
            expandedFiles ? (
              <div style={{ maxHeight: 180, overflowY: 'auto' }}>
                {renderFileTree(files)}
              </div>
            ) : (
              <div style={{
                padding: '4px 8px',
                fontSize: 12,
                color: '#6b7280',
                textAlign: 'center',
              }}>
                {fileCount} 个文件
              </div>
            )
          ) : (
            <div style={{
              fontSize: 11,
              color: '#9ca3af',
              padding: '8px',
              textAlign: 'center',
            }}>
              💾 保存后将生成 SKILL.md 与依赖文件
            </div>
          )}
        </div>
      </div>
    );
  };

  const renderDebugResult = () => {
    if (!debugResult) return null;
    return (
      <div style={{
        padding: '8px 12px',
        background: debugResult.success ? '#f6ffed' : '#fff2f0',
        border: `1px solid ${debugResult.success ? '#b7eb8f' : '#ffccc7'}`,
        borderRadius: 6,
        marginBottom: 12,
        fontSize: 12,
      }}>
        <Space size={6}>
          {debugResult.success
            ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 13 }} />
            : <BugOutlined style={{ color: '#ff4d4f', fontSize: 13 }} />
          }
          <span style={{
            color: debugResult.success ? '#389e0d' : '#cf1322',
            fontWeight: 600,
            fontSize: 13,
          }}>
            {debugResult.success ? '调试通过' : '调试失败'}
          </span>
          {debugResult.message && (
            <Text type="secondary" style={{ fontSize: 11 }}>
              {debugResult.message}
            </Text>
          )}
        </Space>
      </div>
    );
  };

  const renderActionBar = () => (
    <div style={{
      borderTop: '1px solid #e8e8e8',
      padding: '12px 16px',
      background: '#fff',
      flexShrink: 0,
      display: 'flex',
      gap: 10,
    }}>
      <Button
        size="middle"
        icon={<BugOutlined />}
        onClick={onDebug}
        disabled={!draftSkillId}
        style={{ flex: 1 }}
      >
        调试
      </Button>
      <Button
        size="middle"
        type="primary"
        icon={<SaveOutlined />}
        onClick={onSave}
        disabled={!draftSkillId}
        style={{ flex: 1 }}
        loading={streaming}
      >
        {streaming ? '生成中...' : '保存'}
      </Button>
      {onSubmitted && (
        <Button
          size="middle"
          type="primary"
          icon={<ThunderboltOutlined />}
          onClick={() => draftSkillId && onSubmitted(draftSkillId)}
          disabled={!draftSkillId}
          style={{ flex: 1 }}
        >
          提交审核
        </Button>
      )}
    </div>
  );

  const renderEmptyState = () => (
    <div style={{
      padding: '24px 12px',
      textAlign: 'center',
      color: '#6b7280',
    }}>
      <div style={{
        width: 52, height: 52, margin: '0 auto 12px',
        borderRadius: 14,
        background: 'linear-gradient(135deg, #f3f4f6 0%, #e5e7eb 100%)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <ThunderboltOutlined style={{ fontSize: 22, color: '#9ca3af' }} />
      </div>
      <div style={{ fontSize: 14, fontWeight: 600, color: '#374151', marginBottom: 6 }}>
        技能草稿预览
      </div>
      <div style={{ fontSize: 12, lineHeight: '18px', color: '#6b7280', marginBottom: 14 }}>
        在左侧对话框中描述技能需求<br/>
        AI 将自动生成技能结构
      </div>
      <div style={{
        background: '#f9fafb',
        border: '1px solid #e5e7eb',
        borderRadius: 6,
        padding: '8px 10px',
        textAlign: 'left',
      }}>
        <div style={{
          fontSize: 11, color: '#6b7280', marginBottom: 6,
          display: 'flex', alignItems: 'center', gap: 4,
        }}>
          <span>💬</span>
          <span style={{ fontWeight: 500 }}>对话示例</span>
        </div>
        <div style={{
          fontFamily: 'monospace', fontSize: 11, color: '#374151',
          padding: '4px 6px', background: '#fff', borderRadius: 4,
          border: '1px dashed #d1d5db',
        }}>
          "帮我创建一个SQL生成技能"
        </div>
      </div>
      <div style={{
        marginTop: 12, fontSize: 11, color: '#9ca3af',
      }}>
        💡 调试与保存使用底部按钮
      </div>
    </div>
  );

  return (
    <div style={{
      width: 340,
      minWidth: 340,
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      background: '#fafbfc',
      borderLeft: '1px solid #e8e8e8',
      overflow: 'hidden',
      boxShadow: '-2px 0 8px rgba(0,0,0,0.04)',
    }}>
      {renderHeader()}
      {renderStageProgress()}

      <div style={{ flex: 1, overflowY: 'auto', padding: '10px 12px' }}>
        {!skillReady ? (
          renderEmptyState()
        ) : (
          <>
            {renderSkillInfo()}
            {renderInstructions()}
            {renderBindingTools()}
            {renderFileStructure()}
            {renderDebugResult()}
          </>
        )}
      </div>

      {renderActionBar()}
    </div>
  );
};

export default SkillStudioPanel;

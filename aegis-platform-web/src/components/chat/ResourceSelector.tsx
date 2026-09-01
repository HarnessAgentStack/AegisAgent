/**
 * @file 资源选择器组件
 * @description 完整的资源选择器，支持：
 *   - 知识库选择（接入真实API）
 *   - MCP服务选择（接入真实API）
 *   - 多选/单选
 *   - 已选资源展示
 *   - 加载状态
 * @author Aegis
 * @since 2.0.0
 */
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Empty, Spin, Tag, Tooltip, Button, Divider } from 'antd';
import {
  BookOutlined,
  ApiOutlined,
  CheckCircleFilled,
  LoadingOutlined,
  LockOutlined,
} from '@ant-design/icons';
import { getAvailableResources } from '@/api/session';
import type {
  KnowledgeBaseResource,
  McpServiceResource,
  AvailableResource,
} from '@/types/session';

interface ResourceSelectorProps {
  /** 智能体ID */
  agentId?: string;
  /** 已选中的知识库ID（使用字符串避免JavaScript精度丢失） */
  selectedKbIds?: string[];
  /** 已选中的MCP服务ID（使用字符串避免JavaScript精度丢失） */
  selectedMcpIds?: string[];
  /** 选择变更回调 */
  onChange?: (kbIds: string[], mcpIds: string[]) => void;
  /** 是否禁用 */
  disabled?: boolean;
  /** 容器样式 */
  style?: React.CSSProperties;
}

/**
 * 获取安全等级对应的颜色。
 */
const getSecurityLevelColor = (level?: string): string => {
  switch (level) {
    case 'L4':
    case 'SECRET':
      return 'red';
    case 'L3':
    case 'CONFIDENTIAL':
      return 'orange';
    case 'L2':
    case 'INTERNAL':
      return 'blue';
    case 'L1':
    case 'PUBLIC':
      return 'green';
    default:
      return 'default';
  }
};

/**
 * 获取安全等级对应的中文标签。
 */
const getSecurityLevelLabel = (level?: string): string => {
  switch (level) {
    case 'L4':
    case 'SECRET':
      return '绝密';
    case 'L3':
    case 'CONFIDENTIAL':
      return '机密';
    case 'L2':
    case 'INTERNAL':
      return '内部';
    case 'L1':
    case 'PUBLIC':
      return '公开';
    default:
      return level || '未知';
  }
};

/**
 * 资源选择器组件。
 * 用于选择会话级引用的知识库和MCP服务。
 */
export const ResourceSelector: React.FC<ResourceSelectorProps> = ({
  agentId,
  selectedKbIds = [],
  selectedMcpIds = [],
  onChange,
  disabled = false,
  style,
}) => {
  const [loading, setLoading] = useState(false);
  const [resources, setResources] = useState<AvailableResource>({});
  const [localKbIds, setLocalKbIds] = useState<string[]>(selectedKbIds);
  const [localMcpIds, setLocalMcpIds] = useState<string[]>(selectedMcpIds);

  /** 加载可用资源 */
  const loadResources = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getAvailableResources(agentId);
      setResources(data);
    } catch (error) {
      console.error('Failed to load resources:', error);
      // 加载失败时使用空数据，不阻塞用户
      setResources({});
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  /** onChange ref：规避回调引用变化导致的 effect 连锁重建 */
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  });

  /** 初始化加载 */
  useEffect(() => {
    loadResources();
  }, [loadResources]);

  /**
   * 防御：两类失效选择需要自动剔除并同步父组件：
   * 1. 知识库/MCP 被删除或下线——不在可用列表中（后端检索时产生
   *    "知识库不存在"告警，且模型行为退化）
   * 2. 知识库因安全等级（L3 需审批 / L4 拒绝）被标记 selectable=false——
   *    已选后无法通过点击取消（toggle 被 blocked 拦截），必须在此强制清除
   */
  useEffect(() => {
    if (loading) return;
    const kbById = new Map((resources.kbs || []).map(kb => [kb.id, kb]));
    const availableMcpIds = new Set((resources.mcps || []).map(mcp => mcp.id));
    const validKbIds = selectedKbIds.filter(id => {
      const kb = kbById.get(id);
      return kb != null && kb.selectable !== false;
    });
    const validMcpIds = selectedMcpIds.filter(id => availableMcpIds.has(id));
    if (validKbIds.length !== selectedKbIds.length || validMcpIds.length !== selectedMcpIds.length) {
      const removedKb = selectedKbIds.filter(id => {
        const kb = kbById.get(id);
        return kb == null || kb.selectable === false;
      });
      const removedMcp = selectedMcpIds.filter(id => !availableMcpIds.has(id));
      if (removedKb.length > 0) {
        import.meta.env.DEV && console.info('ResourceSelector: 剔除失效/不可选的知识库选择', removedKb);
      }
      if (removedMcp.length > 0) {
        import.meta.env.DEV && console.info('ResourceSelector: 剔除失效的MCP服务选择', removedMcp);
      }
      setLocalKbIds(validKbIds);
      setLocalMcpIds(validMcpIds);
      onChangeRef.current?.(validKbIds, validMcpIds);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resources, loading, selectedKbIds, selectedMcpIds]);

  /** 同步外部选中状态 */
  useEffect(() => {
    setLocalKbIds(selectedKbIds);
  }, [selectedKbIds]);

  useEffect(() => {
    setLocalMcpIds(selectedMcpIds);
  }, [selectedMcpIds]);

  /** 切换知识库选择 */
  const toggleKb = (kb: KnowledgeBaseResource) => {
    if (disabled) return;
    // 档位不匹配的库不可选（后端 markKbItemsByGovernance 标记）
    if (kb.selectable === false) return;
    const newIds = localKbIds.includes(kb.id)
      ? localKbIds.filter(id => id !== kb.id)
      : [...localKbIds, kb.id];
    setLocalKbIds(newIds);
    onChange?.(newIds, localMcpIds);
  };

  /** 切换MCP服务选择 */
  const toggleMcp = (mcp: McpServiceResource) => {
    if (disabled) return;
    const newIds = localMcpIds.includes(mcp.id)
      ? localMcpIds.filter(id => id !== mcp.id)
      : [...localMcpIds, mcp.id];
    setLocalMcpIds(newIds);
    onChange?.(localKbIds, newIds);
  };

  const knowledgeBases = resources.kbs || [];
  const mcpServices = resources.mcps || [];

  return (
    <div style={{ padding: 12, ...style }}>
      {/* 加载状态 */}
      {loading && (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin indicator={<LoadingOutlined style={{ fontSize: 24 }} spin />} />
          <div style={{ marginTop: 8, color: '#999' }}>加载可用资源...</div>
        </div>
      )}

      {!loading && (
        <>
          {/* 知识库部分 */}
          <div>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 12,
              fontWeight: 500,
              color: '#333',
            }}>
              <BookOutlined style={{ color: '#1890ff' }} />
              <span>知识库</span>
              <Tag color="blue" style={{ margin: 0 }}>
                {knowledgeBases.length}
              </Tag>
              {localKbIds.length > 0 && (
                <Tag color="green" style={{ margin: 0 }}>
                  已选 {localKbIds.length}
                </Tag>
              )}
            </div>

            {knowledgeBases.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无可用知识库"
                style={{ margin: '16px 0' }}
              />
            ) : (
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))',
                gap: 8,
                marginBottom: 16,
              }}>
                {knowledgeBases.map(kb => {
                  const isSelected = localKbIds.includes(kb.id);
                  const kbBlocked = kb.selectable === false;
                  const unpublished = !!kb.lifeStatus && kb.lifeStatus !== 'PUBLISHED';
                  return (
                    <Tooltip
                      key={kb.id}
                      title={kbBlocked
                        ? (kb.blockReason || '知识库安全等级不允许直接检索')
                        : unpublished
                          ? `${kb.description || ''}（未发布知识库，仅创建者本人可引用）`.trim()
                          : (kb.description || '')}
                    >
                    <div
                      onClick={() => toggleKb(kb)}
                      style={{
                        padding: '10px 12px',
                        border: `1px solid ${isSelected ? '#1890ff' : '#e0e0e0'}`,
                        background: kbBlocked ? '#fafafa' : (isSelected ? '#e6f7ff' : '#fff'),
                        borderRadius: 8,
                        cursor: (disabled || kbBlocked) ? 'not-allowed' : 'pointer',
                        transition: 'all 0.2s',
                        opacity: (disabled || kbBlocked) ? 0.55 : 1,
                        position: 'relative',
                      }}
                    >
                      <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                      }}>
                        <span style={{
                          fontSize: 13,
                          color: kbBlocked ? '#999' : '#333',
                          fontWeight: 500,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}>
                          {kb.name}
                        </span>
                        {kbBlocked ? (
                          <LockOutlined style={{ color: '#faad14' }} />
                        ) : isSelected && (
                          <CheckCircleFilled style={{ color: '#1890ff' }} />
                        )}
                      </div>
                      {kb.description && !kbBlocked && (
                        <div style={{
                          fontSize: 11,
                          color: '#999',
                          marginTop: 4,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}>
                          {kb.description}
                        </div>
                      )}
                      <div style={{
                        display: 'flex',
                        gap: 6,
                        marginTop: 4,
                      }}>
                        {unpublished && (
                          <Tag
                            color="gold"
                            style={{ margin: 0, fontSize: 10 }}
                          >
                            未发布
                          </Tag>
                        )}
                        {kb.securityLevel && (
                          <Tag
                            color={getSecurityLevelColor(kb.securityLevel)}
                            style={{ margin: 0, fontSize: 10 }}
                          >
                            {getSecurityLevelLabel(kb.securityLevel)}
                          </Tag>
                        )}
                        {kb.documentCount != null && (
                          <span style={{ fontSize: 11, color: '#bbb' }}>
                            📄 {kb.documentCount} 个文档
                          </span>
                        )}
                      </div>
                    </div>
                    </Tooltip>
                  );
                })}
              </div>
            )}
          </div>

          <Divider style={{ margin: '8px 0' }} />

          {/* MCP 服务部分 */}
          <div>
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 12,
              fontWeight: 500,
              color: '#333',
            }}>
              <ApiOutlined style={{ color: '#722ed1' }} />
              <span>MCP 服务</span>
              <Tag color="purple" style={{ margin: 0 }}>
                {mcpServices.length}
              </Tag>
              {localMcpIds.length > 0 && (
                <Tag color="green" style={{ margin: 0 }}>
                  已选 {localMcpIds.length}
                </Tag>
              )}
            </div>

            {mcpServices.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无可用MCP服务"
                style={{ margin: '16px 0' }}
              />
            ) : (
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))',
                gap: 8,
              }}>
                {mcpServices.map(mcp => {
                  const isSelected = localMcpIds.includes(mcp.id);
                  return (
                    <div
                      key={mcp.id}
                      onClick={() => toggleMcp(mcp)}
                      style={{
                        padding: '10px 12px',
                        border: `1px solid ${isSelected ? '#722ed1' : '#e0e0e0'}`,
                        background: isSelected ? '#f9f0ff' : '#fff',
                        borderRadius: 8,
                        cursor: disabled ? 'not-allowed' : 'pointer',
                        transition: 'all 0.2s',
                        opacity: disabled ? 0.6 : 1,
                        position: 'relative',
                      }}
                    >
                      <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                      }}>
                        <span style={{
                          fontSize: 13,
                          color: '#333',
                          fontWeight: 500,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}>
                          {mcp.name}
                        </span>
                        {isSelected && (
                          <CheckCircleFilled style={{ color: '#722ed1' }} />
                        )}
                      </div>
                      {mcp.description && (
                        <Tooltip title={mcp.description}>
                          <div style={{
                            fontSize: 11,
                            color: '#999',
                            marginTop: 4,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                          }}>
                            {mcp.description}
                          </div>
                        </Tooltip>
                      )}
                      <div style={{
                        display: 'flex',
                        gap: 6,
                        marginTop: 4,
                      }}>
                        {mcp.securityLevel && (
                          <Tag
                            color={getSecurityLevelColor(mcp.securityLevel)}
                            style={{ margin: 0, fontSize: 10 }}
                          >
                            {getSecurityLevelLabel(mcp.securityLevel)}
                          </Tag>
                        )}
                        {mcp.toolCount != null && (
                          <span style={{ fontSize: 11, color: '#bbb' }}>
                            🔧 {mcp.toolCount} 个工具
                          </span>
                        )}
                        {mcp.subsCount != null && mcp.subsCount > 0 && (
                          <span style={{ fontSize: 11, color: '#bbb' }}>
                            ⭐ {mcp.subsCount} 订阅
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* 操作按钮 */}
          {(localKbIds.length > 0 || localMcpIds.length > 0) && !disabled && (
            <div style={{
              marginTop: 16,
              padding: '8px 12px',
              background: '#f5f5f5',
              borderRadius: 6,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}>
              <span style={{ fontSize: 12, color: '#666' }}>
                已选择 {localKbIds.length + localMcpIds.length} 个资源
              </span>
              <Button
                size="small"
                onClick={() => {
                  setLocalKbIds([]);
                  setLocalMcpIds([]);
                  onChange?.([], []);
                }}
              >
                清空选择
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default ResourceSelector;

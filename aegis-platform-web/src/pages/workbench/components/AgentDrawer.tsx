/**
 * @file 智能体切换抽屉
 * @description 从 Workbench 抽取的 Drawer 组件，展示智能体列表（通用 / 我的 / 订阅）
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Drawer, Spin, Tag } from 'antd';
import type { Agent } from '@/types/agent';
import { AgentType, LifeStatus } from '@/types/enum';

interface AgentDrawerProps {
  open: boolean;
  agents: Agent[];
  agentsLoading: boolean;
  currentAgentId?: string;
  onClose: () => void;
  onSelect: (agentId: string) => void;
}

export const AgentDrawer: React.FC<AgentDrawerProps> = ({
  open, agents, agentsLoading, currentAgentId, onClose, onSelect,
}) => {
  return (
    <Drawer
      title="切换智能体"
      placement="right"
      width={300}
      open={open}
      onClose={onClose}
      getContainer={() => document.getElementById('chat-container') || document.body}
      styles={{ wrapper: { position: 'absolute' }, body: { padding: '8px 12px' } }}
    >
      <Spin spinning={agentsLoading}>
        {agents.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 40, color: '#9ca3af' }}>暂无智能体</div>
        ) : (
          agents.map((a) => (
            <div
              key={a.id}
              onClick={() => { onSelect(a.id); onClose(); }}
              style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px',
                borderRadius: 8, cursor: 'pointer', marginBottom: 4,
                background: a.id === currentAgentId ? '#eef2ff' : 'transparent',
                border: a.id === currentAgentId ? '1px solid #4f46e5' : '1px solid transparent',
                transition: 'all .15s',
              }}
            >
              <span style={{ fontSize: 24 }}>{a.icon || '🤖'}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 14 }}>{a.agentName}</div>
                <div style={{ fontSize: 12, color: '#6b7280', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {a.description || (a.agentType === AgentType.UNIVERSAL ? '通用智能体' : '应用智能体')}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
                {a.agentType === AgentType.UNIVERSAL && <Tag color="green" style={{ fontSize: 10, margin: 0 }}>通用</Tag>}
                {a.agentType !== AgentType.UNIVERSAL && (a.lifeStatus === LifeStatus.PUBLISHED || a.lifeStatus === LifeStatus.REVIEWING) && (
                  <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>共享</Tag>
                )}
                {a.agentType !== AgentType.UNIVERSAL && a.lifeStatus === LifeStatus.DRAFT && (
                  <Tag color="default" style={{ fontSize: 10, margin: 0 }}>草稿</Tag>
                )}
                {a.agentType !== AgentType.UNIVERSAL && a.lifeStatus === LifeStatus.REJECTED && (
                  <Tag color="red" style={{ fontSize: 10, margin: 0 }} title={a.rejectReason || '已驳回'}>驳回</Tag>
                )}
                {a.agentType !== AgentType.UNIVERSAL && a.lifeStatus === LifeStatus.ARCHIVED && (
                  <Tag color="default" style={{ fontSize: 10, margin: 0, opacity: 0.6 }}>已归档</Tag>
                )}
              </div>
            </div>
          ))
        )}
      </Spin>
    </Drawer>
  );
};

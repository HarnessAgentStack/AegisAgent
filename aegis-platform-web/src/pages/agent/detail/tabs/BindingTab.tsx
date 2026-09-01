/**
 * @file 资源绑定Tab
 * @description 展示智能体绑定的技能、MCP、知识库等资源。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useMemo } from 'react';
import { Card, Col, Empty, Row, Tag, Typography } from 'antd';
import type { Agent, AgentBindingVO } from '@/types/agent';
import { AgentType } from '@/types/enum';
import { RESOURCE_TYPE_META } from '../constants';

const { Text } = Typography;

/** 资源绑定项（展示后端返回的真实绑定数据） */
const BindingItem: React.FC<{ b: AgentBindingVO }> = ({ b }) => {
  const meta = RESOURCE_TYPE_META[b.resourceType] ?? { label: b.resourceType, icon: '📦' };
  const bindingTypeLabel = b.bindingType === 'DYNAMIC' ? '动态' : '固定';
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '10px 12px',
        background: '#f9fafb',
        borderRadius: 8,
        marginBottom: 6,
      }}
    >
      <div
        style={{
          width: 32,
          height: 32,
          borderRadius: 8,
          background: '#fff',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 16,
        }}
      >
        {meta.icon}
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 600 }}>
          {meta.label} #{b.resourceId}
          {b.enabled === false && (
            <Tag color="default" style={{ marginLeft: 6, fontSize: 10 }}>
              已停用
            </Tag>
          )}
        </div>
        <div style={{ fontSize: 11, color: '#9ca3af' }}>
          {bindingTypeLabel}绑定 · 版本 {b.resourceVersion ?? 'latest'}
        </div>
      </div>
      <Tag color={b.bindingType === 'DYNAMIC' ? 'blue' : 'green'}>{bindingTypeLabel}</Tag>
    </div>
  );
};

interface BindingTabProps {
  agent: Agent;
}

const BindingTab: React.FC<BindingTabProps> = ({ agent }) => {
  const isUniversal = agent.agentType === AgentType.UNIVERSAL;

  /** 按资源类型分组绑定关系（来自后端 agent.bindings） */
  const groupedBindings = useMemo(() => {
    const groups: Record<string, AgentBindingVO[]> = {
      SKILL: [],
      MCP_SERVICE: [],
      KNOWLEDGE_BASE: [],
      TOOL: [],
      DATASET: [],
    };
    if (agent?.bindings) {
      for (const b of agent.bindings) {
        const key = b.resourceType ?? 'TOOL';
        if (!groups[key]) groups[key] = [];
        groups[key].push(b);
      }
    }
    return groups;
  }, [agent]);

  return (
    <div>
      <Card style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 13 }}>
          {isUniversal
            ? '通用助手按你已订阅的资源动态调度，以下为当前已加载资源：'
            : '该智能体绑定了以下资源，调用时自动挂载：'}
        </Text>
      </Card>
      <Row gutter={16}>
        {(['SKILL', 'MCP_SERVICE', 'KNOWLEDGE_BASE'] as const).map((rtype) => {
          const list = groupedBindings[rtype] ?? [];
          const meta = RESOURCE_TYPE_META[rtype];
          return (
            <Col span={8} key={rtype}>
              <Card title={`${meta.icon} ${meta.label} (${list.length})`} size="small">
                {list.length === 0 ? (
                  <Empty description="未绑定" />
                ) : (
                  list.map((b) => <BindingItem key={`${b.resourceType}-${b.resourceId}`} b={b} />)
                )}
              </Card>
            </Col>
          );
        })}
      </Row>
    </div>
  );
};

export default BindingTab;

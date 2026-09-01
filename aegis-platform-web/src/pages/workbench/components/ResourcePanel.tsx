/**
 * @file 资源选择 Modal（知识库 + MCP）
 * @description 从 Workbench 抽取的 Modal 组件
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Modal, Button } from 'antd';
import { BookOutlined, ApiOutlined } from '@ant-design/icons';
import { ResourceSelector } from '@/components/chat/ResourceSelector';

interface ResourcePanelProps {
  open: boolean;
  onCancel: () => void;
  agentId?: string;
  selectedKbIds: string[];
  selectedMcpIds: string[];
  disabled?: boolean;
  onChange: (kbIds: string[], mcpIds: string[]) => void;
}

export const ResourcePanel: React.FC<ResourcePanelProps> = ({
  open, onCancel, agentId, selectedKbIds, selectedMcpIds, disabled, onChange,
}) => (
  <Modal
    title={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><BookOutlined style={{ color: '#1890ff' }} /><ApiOutlined style={{ color: '#722ed1' }} /><span>选择资源（知识库 & MCP服务）</span></div>}
    open={open} onCancel={onCancel} width={680} okText="确定" cancelText="取消"
    footer={[
      <Button key="clear" onClick={() => onChange([], [])} disabled={selectedKbIds.length === 0 && selectedMcpIds.length === 0}>清空选择</Button>,
      <Button key="ok" type="primary" onClick={onCancel}>确定 ({selectedKbIds.length + selectedMcpIds.length})</Button>,
    ]}
  >
    <div style={{ maxHeight: 500, overflowY: 'auto' }}>
      <ResourceSelector
        agentId={agentId} selectedKbIds={selectedKbIds} selectedMcpIds={selectedMcpIds}
        onChange={onChange} disabled={disabled}
      />
    </div>
  </Modal>
);

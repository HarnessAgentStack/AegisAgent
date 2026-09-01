/**
 * @file 技能选择 Modal
 * @description 从 Workbench 抽取的 Modal 组件
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Modal, Button, Spin, Tag } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import type { SkillRef } from '@/api/session';
import type { AgentSkill } from '@/types/session';

interface SkillPanelProps {
  open: boolean;
  onClose: () => void;
  skills: AgentSkill[];
  skillsLoading: boolean;
  selected: SkillRef[];
  onChange: (list: SkillRef[]) => void;
}

export const SkillPanel: React.FC<SkillPanelProps> = ({ open, onClose, skills, skillsLoading, selected, onChange }) => (
  <Modal
    title={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><ThunderboltOutlined style={{ color: '#faad14' }} /><span>选择技能</span><Tag color="gold" style={{ marginLeft: 'auto', marginRight: 0 }}>{skills.length} 个可用</Tag></div>}
    open={open} onCancel={onClose} width={520}
    footer={[
      <Button key="cancel" onClick={onClose}>取消</Button>,
      <Button key="clear" onClick={() => onChange([])} disabled={selected.length === 0}>清空</Button>,
      <Button key="ok" type="primary" onClick={onClose}>确定 ({selected.length})</Button>,
    ]}
  >
    <Spin spinning={skillsLoading}>
      <div style={{ maxHeight: 360, overflowY: 'auto' }}>
        {skills.length === 0 && !skillsLoading ? (
          <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>暂无可用技能</div>
        ) : skills.map((skill) => {
          const isSelected = selected.some(s => s.skillCode === skill.skillCode);
          return (
            <div key={skill.skillCode} onClick={() => {
              onChange(isSelected
                ? selected.filter(s => s.skillCode !== skill.skillCode)
                : [...selected, { skillCode: skill.skillCode, version: skill.version }]);
            }}
              style={{ padding: 10, borderRadius: 8, border: isSelected ? '1px solid #faad14' : '1px solid #f0f0f0', background: isSelected ? '#fffbe6' : '#fafafa', cursor: 'pointer', marginBottom: 8 }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <ThunderboltOutlined style={{ color: '#faad14' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontWeight: 500, fontSize: 13 }}>{skill.skillName}</span>
                    {skill.isSystem && <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>系统</Tag>}
                    {skill.category && <Tag style={{ fontSize: 10, margin: 0 }}>{skill.category}</Tag>}
                  </div>
                  {skill.description && <div style={{ fontSize: 12, color: '#999', marginTop: 2 }}>{skill.description}</div>}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </Spin>
  </Modal>
);

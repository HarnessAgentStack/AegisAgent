import React, { useState } from 'react';
import { Card, Tag, Button, Space, Divider, Progress } from 'antd';
import {
  ThunderboltOutlined,
  BulbOutlined,
  CodeOutlined,
  SafetyCertificateOutlined,
  EditOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';

interface SkillCreatorGuideProps {
  onSendMessage: (text: string) => void;
  skillName?: string;
  skillCode?: string;
  creationStage?: number;
}

const QUICK_PROMPTS = [
  {
    icon: '🛠️',
    title: '数据查询技能',
    desc: '创建一个能查询数据库的技能',
    template: '帮我创建一个数据查询技能，能够根据用户输入的条件从数据库中查询相关信息。',
  },
  {
    icon: '📊',
    title: '报告生成技能',
    desc: '创建一个自动生成报告的技能',
    template: '帮我创建一个报告生成技能，能够根据输入的数据自动生成格式化的分析报告。',
  },
  {
    icon: '💬',
    title: '客服问答技能',
    desc: '创建一个智能客服问答技能',
    template: '帮我创建一个客服问答技能，能够回答用户关于产品的常见问题。',
  },
  {
    icon: '🔄',
    title: '数据转换技能',
    desc: '创建一个数据格式转换技能',
    template: '帮我创建一个数据转换技能，能够在不同格式（JSON、XML、CSV）之间转换数据。',
  },
];

const TEMPLATE_PROMPTS = [
  {
    title: '📋 基础信息',
    tips: [
      '技能名称要简洁明了，如"SQL查询生成"',
      '描述技能的核心功能和适用场景',
      '指定技能类型：原子技能（单一功能）或组合技能（多步骤）',
    ],
  },
  {
    title: '⚙️ 执行逻辑',
    tips: [
      '描述技能的输入参数（名称、类型、是否必填）',
      '定义技能的执行步骤和判断规则',
      '说明技能的输出格式',
    ],
  },
  {
    title: '🔒 安全配置',
    tips: [
      '选择安全等级：L1公开/L2内部/L3机密/L4绝密',
      '绑定技能需要使用的工具',
      '配置工具参数映射',
    ],
  },
];

export const SkillCreatorGuide: React.FC<SkillCreatorGuideProps> = ({
  onSendMessage,
  skillName,
  skillCode,
  creationStage = 0,
}) => {
  const [activeTab, setActiveTab] = useState<'quick' | 'template'>('quick');

  const handleQuickPrompt = (template: string) => {
    onSendMessage(template);
  };

  const handleTemplatePrompt = () => {
    const prompt = `我想创建一个技能，请引导我完成配置。\n\n以下是我的初步想法：\n1. 技能类型：原子技能（单一功能）\n2. 安全等级：L1 公开\n3. 请帮我设计完整的技能定义`;
    onSendMessage(prompt);
  };

  const renderProgress = () => {
    if (creationStage === 0) return null;

    const stages = [
      { title: '技能创建', status: creationStage >= 1 ? 'done' : 'active' },
      { title: '安全扫描', status: creationStage >= 2 ? 'done' : 'pending' },
      { title: '完成交付', status: creationStage >= 3 ? 'done' : 'pending' },
    ];

    return (
      <Card
        size="small"
        style={{ marginBottom: 16, background: '#f6ffed', borderColor: '#b7eb8f' }}
        bodyStyle={{ padding: '12px 16px' }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Progress
            percent={creationStage * 33}
            size="small"
            status={creationStage >= 3 ? 'success' : 'active'}
            style={{ width: 120 }}
          />
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 13, fontWeight: 500, marginBottom: 4 }}>
              {creationStage < 3 ? '技能创建进行中...' : '🎉 技能创建完成！'}
            </div>
            <div style={{ display: 'flex', gap: 8, fontSize: 11, color: '#6b7280' }}>
              {stages.map((s, i) => (
                <span
                  key={i}
                  style={{
                    color: s.status === 'done' ? '#52c41a' : s.status === 'active' ? '#1677ff' : '#999',
                  }}
                >
                  {s.status === 'done' ? '✓ ' : ''}{s.title}
                  {i < stages.length - 1 && ' →'}
                </span>
              ))}
            </div>
          </div>
          {creationStage >= 3 && skillName && (
            <Space>
              <Tag color="green">
                <CheckCircleOutlined /> {skillName}
              </Tag>
              {skillCode && <Tag>{skillCode}</Tag>}
            </Space>
          )}
        </div>
      </Card>
    );
  };

  return (
    <div style={{ padding: '16px 24px' }}>
      {renderProgress()}

      <div
        style={{
          background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
          borderRadius: 16,
          padding: '32px',
          color: '#fff',
          marginBottom: 20,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
          <ThunderboltOutlined style={{ fontSize: 32 }} />
          <div>
            <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>技能创建助手</h2>
            <p style={{ margin: 0, fontSize: 13, opacity: 0.9 }}>
              我是你的技能开发助手。描述你想要创建的技能，我会帮你完成配置、调试和交付。
            </p>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
          <Button
            type="primary"
            size="large"
            icon={<BulbOutlined />}
            onClick={handleTemplatePrompt}
            style={{
              background: '#fff',
              color: '#667eea',
              border: 'none',
              fontWeight: 500,
            }}
          >
            开始创建
          </Button>
          <Button
            size="large"
            ghost
            icon={<EditOutlined />}
            onClick={() => onSendMessage('我想修改已有的技能...')}
          >
            修改现有技能
          </Button>
        </div>
      </div>

      <Card
        size="small"
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <BulbOutlined style={{ color: '#faad14' }} />
            <span>快速开始</span>
          </div>
        }
        extra={
          <div style={{ display: 'flex', gap: 8 }}>
            <Button
              size="small"
              type={activeTab === 'quick' ? 'primary' : 'default'}
              onClick={() => setActiveTab('quick')}
            >
              示例
            </Button>
            <Button
              size="small"
              type={activeTab === 'template' ? 'primary' : 'default'}
              onClick={() => setActiveTab('template')}
            >
              模板
            </Button>
          </div>
        }
      >
        {activeTab === 'quick' ? (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
              gap: 12,
            }}
          >
            {QUICK_PROMPTS.map((prompt, i) => (
              <div
                key={i}
                onClick={() => handleQuickPrompt(prompt.template)}
                style={{
                  padding: 16,
                  borderRadius: 12,
                  border: '1px solid #e5e7eb',
                  cursor: 'pointer',
                  transition: 'all 0.2s',
                }}
                className="skill-prompt-card"
              >
                <div style={{ fontSize: 24, marginBottom: 8 }}>{prompt.icon}</div>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>{prompt.title}</div>
                <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 8 }}>{prompt.desc}</div>
                <div style={{ fontSize: 11, color: '#999', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                  {prompt.template}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div>
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>创建一个技能需要考虑：</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
                {TEMPLATE_PROMPTS.map((section, i) => (
                  <div key={i} style={{ padding: 12, background: '#f9fafb', borderRadius: 8 }}>
                    <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 8 }}>{section.title}</div>
                    <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: '#6b7280' }}>
                      {section.tips.map((tip, j) => (
                        <li key={j} style={{ marginBottom: 4 }}>{tip}</li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </div>

            <Divider style={{ margin: '12px 0' }} />

            <div style={{ display: 'flex', gap: 8 }}>
              <Button type="primary" icon={<CodeOutlined />} onClick={handleTemplatePrompt}>
                使用模板创建
              </Button>
              <Button icon={<BulbOutlined />} onClick={() => onSendMessage('给我一些技能创建的建议')}>
                获取灵感
              </Button>
            </div>
          </div>
        )}
      </Card>

      <Card size="small" style={{ marginTop: 16 }} bodyStyle={{ padding: '12px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <SafetyCertificateOutlined style={{ color: '#52c41a', fontSize: 20 }} />
          <div style={{ flex: 1, fontSize: 12, color: '#6b7280' }}>
            <strong style={{ color: '#374151' }}>安全提示：</strong>
            创建的技能会经过自动安全扫描，确保没有注入风险和敏感内容。
            建议使用结构化模板描述技能，便于AI理解和生成。
          </div>
          <Tag color="green">自动扫描</Tag>
        </div>
      </Card>
    </div>
  );
};

export default SkillCreatorGuide;

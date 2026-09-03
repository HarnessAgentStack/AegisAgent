/**
 * @file 安全策略管理
 * @description 4个子标签（工具管控/敏感词/脱敏/出站）
 *              安全级别策略详情已嵌入工具管控决策矩阵的tips按钮
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useState } from 'react';
import { Space, Typography } from 'antd';
import { SafetyCertificateOutlined } from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';
import { SubTabs } from '@/components/common/SubTabs';
import {
  COLOR,
  SECURITY_TABS,
} from './constants';
import ToolPolicyTab from './tabs/ToolPolicyTab';
import SensitiveWordTab from './tabs/SensitiveWordTab';
import MaskRuleTab from './tabs/MaskRuleTab';
import SandboxPolicyTab from './tabs/SandboxPolicyTab';
import OutboundPolicyTab from './tabs/OutboundPolicyTab';

const { Text } = Typography;

const SecurityPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('tool');

  return (
    <div>
      <PageHeader
        title="安全策略管理"
        desc="权限规则、敏感词、脱敏、出站"
        extra={
          <Space>
            <SafetyCertificateOutlined style={{ color: COLOR.primary, fontSize: 18 }} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              策略实时生效
            </Text>
          </Space>
        }
      />
      <SubTabs tabs={SECURITY_TABS} active={activeTab} onChange={setActiveTab} />
      {activeTab === 'tool' && <ToolPolicyTab />}
      {activeTab === 'sandbox' && <SandboxPolicyTab />}
      {activeTab === 'word' && <SensitiveWordTab />}
      {activeTab === 'mask' && <MaskRuleTab />}
      {activeTab === 'out' && <OutboundPolicyTab />}
    </div>
  );
};

export default SecurityPage;

/**
 * @file 高可用与灾备
 * @description 对齐产品原型：部署模式/数据备份两标签，
 *              私有化部署、多活服务、数据备份
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useState } from 'react';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import { HA_TABS } from './constants';
import DeployModeTab from './tabs/DeployModeTab';
import BackupTab from './tabs/BackupTab';

const HAPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('backup');

  return (
    <div>
      <PageHeader title="高可用与灾备" desc="私有化部署、多活服务、数据备份、故障切换、降级策略" />
      <BigTabs tabs={HA_TABS} active={activeTab} onChange={setActiveTab} />
      {activeTab === 'deploy' && <DeployModeTab />}
      {activeTab === 'backup' && <BackupTab />}
    </div>
  );
};

export default HAPage;

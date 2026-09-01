/**
 * @file 技能管理 - 主容器
 * @description 技能市场（卡片网格）+ 我的技能（表格），双标签切换；
 *              创建/编辑技能统一跳转至工作台，实现对话式闭环
 * @author wang.zhen
 * @since 2.0.0
 */
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import type { Skill } from '@/types/resource';
import SkillMarketTab from './SkillMarketTab';
import SkillStudio from './SkillStudio';
import SkillDetailModal from './SkillDetailModal';
import { ROUTE_PATH } from '@/utils/constants';

const SkillPage: React.FC = () => {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<string>('market');

  // ===== Tab badge 总数 =====
  const [marketTotal, setMarketTotal] = useState(0);
  const [myTotal, setMyTotal] = useState(0);

  // ===== 详情弹窗 =====
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<Skill | null>(null);

  // ===== 刷新信号 =====
  const [refreshSignal] = useState(0);

  /** 打开详情弹窗 */
  const openDetail = (record: Skill) => {
    setDetailRecord(record);
    setDetailVisible(true);
  };

  /** 跳转到工作台创建技能 */
  const jumpToWorkbench = (initialPrompt?: string) => {
    const params = new URLSearchParams({ mode: 'skill_creator' });
    if (initialPrompt) {
      params.set('prompt', initialPrompt);
    }
    navigate(`${ROUTE_PATH.WORKBENCH}?${params.toString()}`);
  };

  /** 跳转到工作台编辑技能 */
  const jumpToEdit = (record: Skill) => {
    const params = new URLSearchParams({ mode: 'edit' });
    if (record.id) params.set('skillId', String(record.id));
    if (record.skillCode) params.set('skillCode', record.skillCode);
    navigate(`${ROUTE_PATH.WORKBENCH}?${params.toString()}`);
  };

  /** 详情页的编辑按钮也跳转到工作台 */
  const handleDetailEdit = (record: Skill) => {
    setDetailVisible(false);
    jumpToEdit(record);
  };

  return (
    <div style={{ height: '100%', overflowY: 'auto', paddingRight: 4 }}>
      <PageHeader
        title="技能中心"
        desc="通过 AI 对话快速创建、调试和交付技能，告别繁琐的表单填报"
      />
      <BigTabs
        tabs={[
          { key: 'market', label: '🏪 SKILL市场', badge: marketTotal },
          { key: 'mine', label: '📦 我的SKILL', badge: myTotal },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      />

      <div hidden={activeTab !== 'market'}>
        <SkillMarketTab
          onOpenDetail={openDetail}
          onTotalChange={setMarketTotal}
          refreshSignal={refreshSignal}
        />
      </div>
      <div hidden={activeTab !== 'mine'}>
        <SkillStudio
          onCreate={jumpToWorkbench}
          onEdit={jumpToEdit}
          onOpenDetail={openDetail}
          onTotalChange={setMyTotal}
          refreshSignal={refreshSignal}
        />
      </div>

      <SkillDetailModal
        visible={detailVisible}
        record={detailRecord}
        onCancel={() => setDetailVisible(false)}
        onEdit={handleDetailEdit}
      />
    </div>
  );
};

export default SkillPage;

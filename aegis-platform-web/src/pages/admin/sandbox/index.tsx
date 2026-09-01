/**
 * @file 沙箱资源管理 - 主容器
 * @description 两参数驱动模型：2 个独立标签（基础镜像 / 沙箱池）；
 *              点击沙箱池可进入详情页查看该池下的实例列表；
 *              预热和回收由后端 Reconcile 循环自动执行，策略体系已移除
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useState } from 'react';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import type { SandboxBaseImage, SandboxInstance, SandboxPool } from '@/api/sandbox';
import ImageTab from './ImageTab';
import ImageFormModal from './ImageFormModal';
import PoolTab from './PoolTab';
import PoolFormModal from './PoolFormModal';
import PoolK8sStatusModal from './PoolK8sStatusModal';
import PoolDetail from './PoolDetail';
import InstancePodStatusModal from './InstancePodStatusModal';

const SandboxPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('pool');

  // ===== 池详情视图 =====
  const [viewPool, setViewPool] = useState<SandboxPool | null>(null);

  // ===== Tab badge 总数 =====
  const [imageTotal, setImageTotal] = useState(0);
  const [poolTotal, setPoolTotal] = useState(0);

  // ===== 镜像弹窗 =====
  const [imageFormVisible, setImageFormVisible] = useState(false);
  const [imageEditRecord, setImageEditRecord] = useState<SandboxBaseImage | null>(null);

  // ===== 池弹窗 =====
  const [poolFormVisible, setPoolFormVisible] = useState(false);
  const [poolEditRecord, setPoolEditRecord] = useState<SandboxPool | null>(null);
  const [poolK8sVisible, setPoolK8sVisible] = useState(false);
  const [poolK8sRecord, setPoolK8sRecord] = useState<SandboxPool | null>(null);

  // ===== 实例弹窗 =====
  const [podStatusVisible, setPodStatusVisible] = useState(false);
  const [podStatusRecord, setPodStatusRecord] = useState<SandboxInstance | null>(null);

  // ===== 刷新信号 =====
  const [refreshSignal, setRefreshSignal] = useState(0);

  // ===== 镜像 =====
  const openImageCreate = () => {
    setImageEditRecord(null);
    setImageFormVisible(true);
  };
  const openImageEdit = (record: SandboxBaseImage) => {
    setImageEditRecord(record);
    setImageFormVisible(true);
  };
  const onImageSuccess = () => {
    setImageFormVisible(false);
    setRefreshSignal((s) => s + 1);
  };

  // ===== 池 =====
  const openPoolCreate = () => {
    setPoolEditRecord(null);
    setPoolFormVisible(true);
  };
  const openPoolEdit = (record: SandboxPool) => {
    setPoolEditRecord(record);
    setPoolFormVisible(true);
  };
  const onPoolSuccess = () => {
    setPoolFormVisible(false);
    setRefreshSignal((s) => s + 1);
  };
  const showPoolK8s = (record: SandboxPool) => {
    setPoolK8sRecord(record);
    setPoolK8sVisible(true);
  };
  const openPoolDetail = (record: SandboxPool) => {
    setViewPool(record);
  };
  const backToPoolList = () => {
    setViewPool(null);
    setRefreshSignal((s) => s + 1);
  };

  // ===== 实例 =====
  const showPodStatus = (record: SandboxInstance) => {
    setPodStatusRecord(record);
    setPodStatusVisible(true);
  };

  // ===== 池详情视图 =====
  if (viewPool) {
    return (
      <div style={{ height: '100%', overflowY: 'auto', paddingRight: 4 }}>
        <PoolDetail
          poolId={viewPool.id!}
          record={viewPool}
          onBack={backToPoolList}
          onEdit={openPoolEdit}
          onShowK8sStatus={showPoolK8s}
          onShowPodStatus={showPodStatus}
        />

        {/* 池弹窗（详情页也可编辑） */}
        <PoolFormModal
          visible={poolFormVisible}
          editRecord={poolEditRecord}
          onCancel={() => setPoolFormVisible(false)}
          onSuccess={onPoolSuccess}
        />
        <PoolK8sStatusModal
          visible={poolK8sVisible}
          record={poolK8sRecord}
          onCancel={() => setPoolK8sVisible(false)}
        />

        {/* 实例弹窗 */}
        <InstancePodStatusModal
          visible={podStatusVisible}
          record={podStatusRecord}
          onCancel={() => setPodStatusVisible(false)}
        />
      </div>
    );
  }

  return (
    <div style={{ height: '100%', overflowY: 'auto', paddingRight: 4 }}>
      <PageHeader title="沙箱资源管理" desc="基础镜像 · 沙箱池（两参数驱动：min_instances / max_instances），点击沙箱池查看实例详情" />
      <BigTabs
        tabs={[
          { key: 'image', label: '🐳 基础镜像', badge: imageTotal },
          { key: 'pool', label: '🏊 沙箱池', badge: poolTotal },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      />

      <div hidden={activeTab !== 'image'}>
        <ImageTab
          onCreate={openImageCreate}
          onEdit={openImageEdit}
          onTotalChange={setImageTotal}
          refreshSignal={refreshSignal}
        />
      </div>
      <div hidden={activeTab !== 'pool'}>
        <PoolTab
          onCreate={openPoolCreate}
          onEdit={openPoolEdit}
          onShowK8sStatus={showPoolK8s}
          onViewDetail={openPoolDetail}
          onTotalChange={setPoolTotal}
          refreshSignal={refreshSignal}
        />
      </div>

      {/* 镜像弹窗 */}
      <ImageFormModal
        visible={imageFormVisible}
        editRecord={imageEditRecord}
        onCancel={() => setImageFormVisible(false)}
        onSuccess={onImageSuccess}
      />

      {/* 池弹窗 */}
      <PoolFormModal
        visible={poolFormVisible}
        editRecord={poolEditRecord}
        onCancel={() => setPoolFormVisible(false)}
        onSuccess={onPoolSuccess}
      />
      <PoolK8sStatusModal
        visible={poolK8sVisible}
        record={poolK8sRecord}
        onCancel={() => setPoolK8sVisible(false)}
      />

      {/* 实例弹窗 */}
      <InstancePodStatusModal
        visible={podStatusVisible}
        record={podStatusRecord}
        onCancel={() => setPodStatusVisible(false)}
      />
    </div>
  );
};

export default SandboxPage;

/**
 * @file 模型管理
 * @description 3 标签容器：供应商 / 模型实例 / 限流配置
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import { ModelTier } from '@/types/enum';
import { modelApi } from '@/api/model';
import type { ProviderVO, ModelDefVO } from '@/api/model';
import type { ProviderRow, ModelInstanceRow, ModelCapabilities } from './constants';
import ProviderTab from './tabs/ProviderTab';
import ModelInstanceTab from './tabs/ModelInstanceTab';
import RateLimitTab from './tabs/RateLimitTab';

/** 解析后端 capabilities JSON 字符串为对象，失败返回 undefined */
const parseCapabilities = (raw?: string): ModelCapabilities | undefined => {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'object' && parsed !== null ? (parsed as ModelCapabilities) : undefined;
  } catch {
    return undefined;
  }
};

const ModelPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('provider');

  // 共享数据：供应商 + 模型实例（多个 Tab 依赖）
  const [providers, setProviders] = useState<ProviderRow[]>([]);
  const [models, setModels] = useState<ModelInstanceRow[]>([]);

  // 各 Tab 行数（BigTabs 徽标）
  const [providerCount, setProviderCount] = useState(0);
  const [modelCount, setModelCount] = useState(0);
  const [rateLimitCount, setRateLimitCount] = useState(0);

  /** 加载共享数据：供应商、模型实例（模型实例需关联供应商名称） */
  useEffect(() => {
    const fetchSharedData = async () => {
      try {
        const [providerPage, modelList] = await Promise.all([
          modelApi.listProviders(),
          modelApi.listDefs(),
        ]);

        const providerRaw = Array.isArray(providerPage) ? providerPage : providerPage?.records ?? [];
        const providerList: ProviderRow[] = providerRaw.map((p: ProviderVO) => ({
          id: String(p.id),
          providerCode: p.providerCode ?? '',
          providerName: p.providerName ?? '',
          status: (p.status ?? 'PENDING') as ProviderRow['status'],
          endpoint: p.endpoint ?? '',
          modelCount: p.modelCount ?? 0,
          apiKeyMasked: p.apiKeyMasked,
        }));
        setProviders(providerList);

        const modelRows: ModelInstanceRow[] = (modelList ?? []).map((m: ModelDefVO) => ({
          id: String(m.id),
          modelCode: m.modelCode ?? '',
          modelName: m.modelName ?? '',
          modelType: m.modelType,
          tier: (m.tier ?? ModelTier.STANDARD) as ModelTier,
          providerId: String(m.providerId),
          providerName: providerList.find((p) => p.id === String(m.providerId))?.providerName ?? '-',
          contextWindow: m.contextWindow ?? 0,
          status: (m.status ?? 'DISABLED') as ModelInstanceRow['status'],
          capabilities: parseCapabilities(m.capabilities),
        }));
        setModels(modelRows);
      } catch (err) {
        console.error('加载模型管理数据失败', err);
      }
    };
    fetchSharedData();
  }, []);

  return (
    <div>
      <PageHeader title="模型管理" desc="供应商 · 模型实例 · 限流配置" />
      <BigTabs
        tabs={[
          { key: 'provider', label: '🏭 供应商', badge: providerCount },
          { key: 'model', label: '🧠 模型实例', badge: modelCount },
          { key: 'rateLimit', label: '⚡ 限流配置', badge: rateLimitCount },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      />

      {activeTab === 'provider' && (
        <ProviderTab providers={providers} setProviders={setProviders} onCountChange={setProviderCount} />
      )}
      {activeTab === 'model' && (
        <ModelInstanceTab providers={providers} models={models} setModels={setModels} onCountChange={setModelCount} />
      )}
      {activeTab === 'rateLimit' && (
        <RateLimitTab onCountChange={setRateLimitCount} />
      )}
    </div>
  );
};

export default ModelPage;

/**
 * @file 技能市场 Tab - 卡片网格 + 筛选 + 订阅
 * @description 市场列表展示、关键词/安全级别筛选、分页、真实订阅交互
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState, useCallback } from 'react';
import { App, Button, Col, Input, Pagination, Row, Select, Spin } from 'antd';
import { ResourceCard } from '@/components/common/ResourceCard';
import { EmptyState } from '@/components/common/EmptyState';
import { SecurityLevel } from '@/types/enum';
import type { Skill } from '@/types/resource';
import { skillApi, extractList, extractTotal } from '@/api/resource';
import {
  ICON,
  ICON_BG,
  SECURITY_OPTIONS,
  SECURITY_TAG,
  SKILL_TYPE_TAG,
} from './constants';

interface SkillMarketTabProps {
  onOpenDetail: (record: Skill) => void;
  onTotalChange?: (total: number) => void;
  refreshSignal?: number;
}

const SkillMarketTab: React.FC<SkillMarketTabProps> = ({
  onOpenDetail,
  onTotalChange,
  refreshSignal,
}) => {
  const { message } = App.useApp();
  const [marketKeyword, setMarketKeyword] = useState('');
  const [marketInput, setMarketInput] = useState('');
  const [marketSecurity, setMarketSecurity] = useState<string>('all');
  // P1-ITEM-17：订阅状态筛选（全部/已订阅/未订阅）
  const [subFilter, setSubFilter] = useState<'all' | 'subscribed' | 'unsubscribed'>('all');
  const [marketList, setMarketList] = useState<Skill[]>([]);
  const [marketLoading, setMarketLoading] = useState(false);
  const [marketTotal, setMarketTotal] = useState(0);
  const [marketPage, setMarketPage] = useState(1);
  const [marketSize, setMarketSize] = useState(20);
  const [subscribedIds, setSubscribedIds] = useState<Set<string>>(new Set());

  /** 拉取市场列表 */
  const loadMarket = useCallback(async () => {
    setMarketLoading(true);
    try {
      const res = await skillApi.market({
        scope: 'market',
        keyword: marketKeyword || undefined,
        securityLevel: marketSecurity !== 'all' ? (marketSecurity as SecurityLevel) : undefined,
        page: marketPage,
        size: marketSize,
      });
      const list = extractList(res);
      setMarketList(list);
      const total = extractTotal(res);
      setMarketTotal(total);
      onTotalChange?.(total);

      // P1-ITEM-3：批量查询订阅状态（单次请求替代 N 次 subStatus 调用）
      if (list.length > 0) {
        const ids = list.map((s) => s.id!).filter(Boolean);
        try {
          const subscribedMap = await skillApi.batchSubStatus(ids);
          const subscribedSet = new Set<string>();
          ids.forEach((id) => {
            if (subscribedMap?.[String(id)]) {
              subscribedSet.add(id);
            }
          });
          setSubscribedIds(subscribedSet);
        } catch {
          // 批量查询失败时回退为全部未订阅，不阻断列表渲染
          setSubscribedIds(new Set());
        }
      } else {
        setSubscribedIds(new Set());
      }
    } catch {
      /* 弹错已处理 */
    } finally {
      setMarketLoading(false);
    }
  }, [marketKeyword, marketSecurity, marketPage, marketSize, onTotalChange]);

  // 筛选/分页变化时重新加载
  useEffect(() => {
    loadMarket();
  }, [loadMarket, refreshSignal]);

  /** 订阅/取消订阅（真实 API） */
  const toggleSubscribe = async (skillId: string) => {
    const isSubscribed = subscribedIds.has(skillId);
    try {
      if (isSubscribed) {
        await skillApi.unsubscribe(skillId);
        message.success('已取消订阅');
        setSubscribedIds((prev) => {
          const next = new Set(prev);
          next.delete(skillId);
          return next;
        });
      } else {
        await skillApi.subscribe(skillId);
        message.success('订阅成功');
        setSubscribedIds((prev) => {
          const next = new Set(prev);
          next.add(skillId);
          return next;
        });
      }
      // 刷新列表以获取最新订阅数
      loadMarket();
    } catch {
      /* 错误已由拦截器处理 */
    }
  };

  // ===== P1-ITEM-17：订阅状态前端过滤（基于已有的批量查询结果） =====
  const filteredMarketList = marketList.filter((item) => {
    if (subFilter === 'all') return true;
    const itemId = item.id ?? '';
    const isSub = subscribedIds.has(itemId);
    return subFilter === 'subscribed' ? isSub : !isSub;
  });

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <Input.Search
            placeholder="搜索技能名称"
            value={marketInput}
            onChange={(e) => {
              setMarketInput(e.target.value);
              if (e.target.value === '') {
                setMarketKeyword('');
                setMarketPage(1);
              }
            }}
            onSearch={(v) => {
              setMarketKeyword(v);
              setMarketPage(1);
            }}
            allowClear
            style={{ width: 240 }}
            enterButton
          />
          <Select
            value={marketSecurity}
            onChange={(v) => {
              setMarketSecurity(v);
              setMarketPage(1);
            }}
            options={SECURITY_OPTIONS}
            style={{ width: 140 }}
          />
          {/* P1-ITEM-17：订阅状态筛选（前端过滤，不新增后端请求） */}
          <Select
            value={subFilter}
            onChange={(v) => {
              setSubFilter(v as 'all' | 'subscribed' | 'unsubscribed');
              setMarketPage(1);
            }}
            options={[
              { value: 'all', label: '全部订阅' },
              { value: 'subscribed', label: '已订阅' },
              { value: 'unsubscribed', label: '未订阅' },
            ]}
            style={{ width: 130 }}
          />
        </div>
      </div>

      <Spin spinning={marketLoading}>
        {filteredMarketList.length === 0 && !marketLoading ? (
          <EmptyState
            title="暂无技能"
            desc={subFilter !== 'all' ? '没有符合订阅状态筛选条件的技能，试试调整筛选条件' : '未找到符合条件的技能，试试调整筛选条件'}
          />
        ) : (
          <Row gutter={[16, 16]}>
            {filteredMarketList.map((item) => {
              const itemId = item.id ?? '';
              const isSubscribed = subscribedIds.has(itemId);
              return (
                <Col key={itemId} xs={24} sm={12} lg={6}>
                  <ResourceCard
                    icon={ICON[item.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '🛠️'}
                    iconBg={ICON_BG[item.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '#e6f4ff'}
                    name={item.skillName}
                    desc={item.description ?? '-'}
                    meta={[
                      { label: '订阅', value: String(item.subsCount ?? 0) },
                      { label: '版本', value: item.version ? `v${item.version}` : '-' },
                    ]}
                    tags={[
                      SECURITY_TAG[item.securityLevel],
                      {
                        text: SKILL_TYPE_TAG[item.skillType].text,
                        color: SKILL_TYPE_TAG[item.skillType].color,
                      },
                    ]}
                    actions={[
                      <Button
                        key="sub"
                        size="small"
                        type={isSubscribed ? 'default' : 'primary'}
                        onClick={() => toggleSubscribe(itemId)}
                      >
                        {isSubscribed ? '已订阅' : '订阅'}
                      </Button>,
                      <Button
                        key="detail"
                        size="small"
                        onClick={() => onOpenDetail(item)}
                      >
                        详情
                      </Button>,
                    ]}
                  />
                </Col>
              );
            })}
          </Row>
        )}
        {marketTotal > 0 && (
          <div style={{ marginTop: 16, textAlign: 'right' }}>
            <Pagination
              current={marketPage}
              pageSize={marketSize}
              total={marketTotal}
              showSizeChanger
              showTotal={(t) => `共 ${t} 条`}
              onChange={(p, sz) => {
                setMarketPage(p);
                setMarketSize(sz);
              }}
            />
          </div>
        )}
      </Spin>
    </>
  );
};

export default SkillMarketTab;

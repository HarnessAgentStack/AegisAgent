/**
 * @file 治理档位标签
 * @description 根据治理档位渲染对应颜色与文案的 Tag（取代原安全级别标签）。
 *              治理档位为单一判别器，统一驱动沙箱隔离、工具管控、内容过滤、人审与审计粒度。
 * @author aegis
 * @since 2.0.0
 */
import React from 'react';
import { Tag } from 'antd';
import { GovernanceTier } from '@/types/enum';

interface GovernanceTierTagProps {
  /** 治理档位 */
  tier?: GovernanceTier;
}

/** 治理档位 → 颜色 / 文案 */
const CONFIG: Record<GovernanceTier, { color: string; text: string }> = {
  [GovernanceTier.STANDARD]: { color: 'green', text: '标准档' },
  [GovernanceTier.ENHANCED]: { color: 'gold', text: '增强档' },
  [GovernanceTier.STRICT]: { color: 'red', text: '严格档' },
};

export const GovernanceTierTag: React.FC<GovernanceTierTagProps> = ({ tier }) => {
  if (!tier) return null;
  const cfg = CONFIG[tier] ?? CONFIG[GovernanceTier.STANDARD];
  return <Tag color={cfg.color}>{cfg.text}</Tag>;
};

export default GovernanceTierTag;

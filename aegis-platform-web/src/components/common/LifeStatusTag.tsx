/**
 * @file 生命周期状态标签
 * @description 根据生命周期状态渲染对应 Badge
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Badge } from 'antd';
import { LifeStatus } from '@/types/enum';

interface LifeStatusTagProps {
  /** 生命周期状态 */
  status: LifeStatus;
}

/** 生命周期状态对应 Badge 状态 / 文案 */
const CONFIG: Record<
  LifeStatus,
  { status: 'success' | 'processing' | 'default' | 'error' | 'warning'; text: string }
> = {
  [LifeStatus.DRAFT]: { status: 'default', text: '草稿' },
  [LifeStatus.ACTIVE]: { status: 'success', text: '个人可用' },
  [LifeStatus.REVIEWING]: { status: 'processing', text: '审核中' },
  [LifeStatus.PUBLISHED]: { status: 'success', text: '已发布' },
  [LifeStatus.ARCHIVED]: { status: 'warning', text: '已归档' },
  [LifeStatus.REJECTED]: { status: 'error', text: '已拒绝' },
};

export const LifeStatusTag: React.FC<LifeStatusTagProps> = ({ status }) => {
  const cfg = CONFIG[status] ?? CONFIG[LifeStatus.DRAFT];
  return <Badge status={cfg.status} text={cfg.text} />;
};

export default LifeStatusTag;
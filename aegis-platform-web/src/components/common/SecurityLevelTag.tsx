/**
 * @file 安全级别标签
 * @description 根据安全级别渲染对应颜色与文案的 Tag
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Tag } from 'antd';
import { SecurityLevel } from '@/types/enum';

interface SecurityLevelTagProps {
  /** 安全级别 */
  level: SecurityLevel;
}

/** 安全级别 →颜色 / 文案 */
const CONFIG: Record<SecurityLevel, { color: string; text: string }> = {
  [SecurityLevel.L1]: { color: 'green', text: 'L1 公开级' },
  [SecurityLevel.L2]: { color: 'blue', text: 'L2 内部级' },
  [SecurityLevel.L3]: { color: 'orange', text: 'L3 机密级' },
  [SecurityLevel.L4]: { color: 'red', text: 'L4 绝密级' },
};

export const SecurityLevelTag: React.FC<SecurityLevelTagProps> = ({ level }) => {
  const cfg = CONFIG[level] ?? CONFIG[SecurityLevel.L1];
  return <Tag color={cfg.color}>{cfg.text}</Tag>;
};

export default SecurityLevelTag;
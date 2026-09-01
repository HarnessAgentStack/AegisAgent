/**
 * @file 模型档位选择
 * @description 选择智能体使用的模型档位（轻量 / 标准 / 高级 / 旗舰）
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Select } from 'antd';
import { ModelTier } from '@/types/enum';

interface ModelTierSelectorProps {
  /** 当前档位 */
  value?: ModelTier;
  /** 档位变更回调 */
  onChange?: (tier: ModelTier) => void;
  /** 禁用 */
  disabled?: boolean;
}

/** 档位选项 */
const OPTIONS: Array<{ value: ModelTier; label: string; desc: string }> = [
  { value: ModelTier.LIGHT, label: '轻量', desc: '响应迅速，适合简单任务' },
  { value: ModelTier.STANDARD, label: '标准', desc: '均衡稳定，适合通用任务' },
  { value: ModelTier.STRONG, label: '高性能', desc: '更强推理，适合复杂任务' },
];

export const ModelTierSelector: React.FC<ModelTierSelectorProps> = ({
  value,
  onChange,
  disabled,
}) => (
  <Select<ModelTier>
    value={value}
    onChange={onChange}
    disabled={disabled}
    placeholder="选择模型档位"
    options={OPTIONS.map((o) => ({ value: o.value, label: `${o.label}（${o.desc}）` }))}
    style={{ width: '100%' }}
  />
);

export default ModelTierSelector;
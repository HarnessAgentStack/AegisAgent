/**
 * @file 子标签切换
 * @description 原型中的 .monitor-sub-tabs / .monitor-sub-tab 组件，
 *              用于管理控制台页面内的二级子标签切换（如模型管理/安全策略/运行时监管等）。
 *              比 BigTabs 更紧凑，支持横向滚动。
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';

interface SubTab {
  key: string;
  label: string;
  badge?: string | number;
}

interface SubTabsProps {
  tabs: SubTab[];
  active: string;
  onChange: (key: string) => void;
}

export const SubTabs: React.FC<SubTabsProps> = ({ tabs, active, onChange }) => {
  return (
    <div
      style={{
        display: 'flex',
        gap: 4,
        marginBottom: 16,
        borderBottom: '1px solid #e5e7eb',
        overflowX: 'auto',
        flexWrap: 'nowrap',
      }}
    >
      {tabs.map((tab) => {
        const isActive = tab.key === active;
        return (
          <div
            key={tab.key}
            onClick={() => onChange(tab.key)}
            style={{
              padding: '8px 14px',
              fontSize: 13,
              fontWeight: isActive ? 600 : 400,
              color: isActive ? '#4f46e5' : '#6b7280',
              cursor: 'pointer',
              borderBottom: isActive ? '2px solid #4f46e5' : '2px solid transparent',
              marginBottom: '-1px',
              transition: 'all .15s',
              whiteSpace: 'nowrap',
              background: isActive ? '#eef2ff' : 'transparent',
              borderRadius: '6px 6px 0 0',
            }}
          >
            {tab.label}
            {tab.badge !== undefined && (
              <span
                style={{
                  marginLeft: 6,
                  fontSize: 10,
                  padding: '1px 6px',
                  borderRadius: 10,
                  background: isActive ? '#4f46e5' : '#e5e7eb',
                  color: isActive ? '#fff' : '#6b7280',
                  fontWeight: 600,
                }}
              >
                {tab.badge}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default SubTabs;

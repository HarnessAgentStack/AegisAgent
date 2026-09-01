/**
 * @file 大标签切换
 * @description 原型中的 .big-tabs / .big-tab 组件，用于页面内一级标签切换
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';

interface BigTab {
  key: string;
  label: string;
  badge?: string | number;
}

interface BigTabsProps {
  tabs: BigTab[];
  active: string;
  onChange: (key: string) => void;
}

export const BigTabs: React.FC<BigTabsProps> = ({ tabs, active, onChange }) => {
  return (
    <div style={{ display: 'flex', gap: 0, marginBottom: 16, borderBottom: '2px solid #e5e7eb' }}>
      {tabs.map((tab) => {
        const isActive = tab.key === active;
        return (
          <div
            key={tab.key}
            onClick={() => onChange(tab.key)}
            style={{
              padding: '10px 24px',
              fontSize: 14,
              fontWeight: isActive ? 600 : 400,
              color: isActive ? '#4f46e5' : '#6b7280',
              cursor: 'pointer',
              borderBottom: isActive ? '2px solid #4f46e5' : '2px solid transparent',
              marginBottom: '-2px',
              transition: 'all .2s',
              whiteSpace: 'nowrap',
            }}
          >
            {tab.label}
            {tab.badge !== undefined && (
              <span
                style={{
                  marginLeft: 6,
                  fontSize: 11,
                  padding: '1px 7px',
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

export default BigTabs;

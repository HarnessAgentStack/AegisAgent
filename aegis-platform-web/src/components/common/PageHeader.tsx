/**
 * @file 页面头部
 * @description 统一的页面标题 + 描述 + 操作区，对齐原型 .page-title / .page-desc
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Typography } from 'antd';

const { Title, Paragraph } = Typography;

interface PageHeaderProps {
  title: string;
  desc?: string;
  extra?: React.ReactNode;
}

export const PageHeader: React.FC<PageHeaderProps> = ({ title, desc, extra }) => {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
      <div>
        <Title level={4} style={{ marginBottom: 4 }}>{title}</Title>
        {desc && <Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 13 }}>{desc}</Paragraph>}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  );
};

export default PageHeader;

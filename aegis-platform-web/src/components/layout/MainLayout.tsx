/**
 * @file 主布局
 * @description 侧边栏 + 顶栏 + 内容区，含路由 Outlet 与懒加载 Suspense
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useState } from 'react';
import { Layout, Spin } from 'antd';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';

const { Content } = Layout;

/**
 * 主布局组件
 * @description 平台主框架，包裹所有受保护页面（通过 Outlet 渲染子路由）
 */
export const MainLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <Layout style={{ height: '100vh', minHeight: '100vh' }}>
      <Sidebar collapsed={collapsed} />
      <Layout style={{ minWidth: 0 }}>
        <Header collapsed={collapsed} onToggle={() => setCollapsed((v) => !v)} />
        <Content
          style={{
            margin: 0,
            padding: 16,
            background: 'var(--color-bg-layout)',
            flex: 1,
            minHeight: 0,
            overflow: 'auto',
          }}
        >
          <React.Suspense fallback={<Spin style={{ display: 'block', margin: '120px auto' }} />}>
            <Outlet />
          </React.Suspense>
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
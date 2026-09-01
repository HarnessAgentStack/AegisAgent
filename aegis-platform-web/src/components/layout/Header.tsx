/**
 * @file 顶栏
 * @description 折叠按钮 + 全局搜索 + 通知 + 用户菜单，对齐产品原型设计
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Avatar, Button, Dropdown, Layout, Space, Tooltip } from 'antd';
import {
  BulbFilled,
  BulbOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';
import { ROUTE_PATH } from '@/utils/constants';
import { useThemeStore } from '@/stores/themeStore';

const { Header: AntHeader } = Layout;

interface HeaderProps {
  collapsed: boolean;
  onToggle: () => void;
}

export const Header: React.FC<HeaderProps> = ({ collapsed, onToggle }) => {
  const navigate = useNavigate();
  const { user, signOut } = useAuth();
  const themeMode = useThemeStore((s) => s.mode);
  const toggleTheme = useThemeStore((s) => s.toggle);

  const handleSignOut = async () => {
    await signOut();
    navigate(ROUTE_PATH.LOGIN, { replace: true });
  };

  const userMenu = {
    items: [
      { key: 'profile', icon: <UserOutlined />, label: '个人设置', onClick: () => navigate(ROUTE_PATH.PROFILE) },
      { type: 'divider' as const },
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: handleSignOut },
    ],
  };

  return (
    <AntHeader
      style={{
        height: 60,
        padding: '0 24px',
        background: 'var(--color-bg-topbar)',
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        borderBottom: '1px solid var(--color-border-secondary)',
        position: 'sticky',
        top: 0,
        zIndex: 9,
      }}
    >
      {/* 折叠按钮 */}
      <Button
        type="text"
        onClick={onToggle}
        icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
        style={{ fontSize: 18 }}
      />

      {/* 右侧操作区 */}
      <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8 }}>
        {/* 主题切换（任务 13） */}
        <Tooltip title={themeMode === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}>
          <Button
            type="text"
            onClick={toggleTheme}
            icon={themeMode === 'dark' ? <BulbFilled style={{ fontSize: 18 }} /> : <BulbOutlined style={{ fontSize: 18 }} />}
          />
        </Tooltip>
        <Dropdown menu={userMenu} placement="bottomRight">
          <Space style={{ cursor: 'pointer', padding: '0 4px' }}>
            <Avatar
              size="small"
              icon={<UserOutlined />}
              src={user?.avatar}
              style={{ background: '#4f46e5' }}
            />
            <span style={{ fontSize: 14, color: 'var(--color-text-primary)' }}>
              {user?.nickname ?? user?.username ?? '用户'}
            </span>
          </Space>
        </Dropdown>
      </div>
    </AntHeader>
  );
};

export default Header;

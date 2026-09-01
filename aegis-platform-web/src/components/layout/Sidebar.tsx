/**
 * @file 侧边导航
 * @description 双模式（员工工作台/管理控制台）侧边栏，分组菜单 + 深色主题，
 *              对齐产品原型设计。模式切换时自动导航到对应默认页面。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useMemo, useState } from 'react';
import { Layout } from 'antd';
import {
  AppstoreOutlined,
  AuditOutlined,
  BankOutlined,
  DatabaseOutlined,
  EyeOutlined,
  HomeOutlined,
  KeyOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  ClusterOutlined,
  FileSearchOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { ROUTE_PATH, type SidebarMode } from '@/utils/constants';
import { usePermission } from '@/hooks/usePermission';

const { Sider } = Layout;

/** 菜单项定义 */
interface MenuItemDef {
  path: string;
  icon: React.ReactNode;
  label: string;
  badge?: string;
  permission?: string;
}

/** 菜单组定义 */
interface MenuGroupDef {
  group: string;
  items: MenuItemDef[];
}

/** 员工工作台菜单 */
const USER_MENUS: MenuGroupDef[] = [
  {
    group: '工作台',
    items: [{ path: ROUTE_PATH.WORKBENCH, icon: <HomeOutlined />, label: '我的工作台' }],
  },
  {
    group: '服务中心',
    items: [
      { path: ROUTE_PATH.AGENT_LIST, icon: <RobotOutlined />, label: '智能体中心' },
      { path: ROUTE_PATH.RESOURCE_SKILL, icon: <ThunderboltOutlined />, label: '技能中心' },
      { path: ROUTE_PATH.RESOURCE_MCP, icon: <ApiOutlined />, label: 'MCP中心' },
      { path: ROUTE_PATH.RESOURCE_KNOWLEDGE, icon: <DatabaseOutlined />, label: '知识库中心' },
    ],
  },
];

/** 管理控制台菜单 */
const ADMIN_MENUS: MenuGroupDef[] = [
  {
    group: '资源与模型',
    items: [
      {
        path: ROUTE_PATH.ADMIN_SANDBOX,
        icon: <AppstoreOutlined />,
        label: '沙箱管理',
        permission: 'sandbox:view',
      },
      { path: ROUTE_PATH.MODEL, icon: <ClusterOutlined />, label: '模型管理', permission: 'model:view' },
      {
        path: ROUTE_PATH.SECURITY,
        icon: <SafetyCertificateOutlined />,
        label: '安全策略',
        permission: 'security:policy:view',
      },
    ],
  },
  {
    group: '监管与审计',
    items: [
      {
        path: ROUTE_PATH.ADMIN_OBSERVE,
        icon: <EyeOutlined />,
        label: '可观测监控',
        permission: 'observe:view',
      },
      { path: ROUTE_PATH.AUDIT, icon: <AuditOutlined />, label: '审计日志', permission: 'security:audit:view' },
    ],
  },
  {
    group: '审核中心',
    items: [
      {
        path: ROUTE_PATH.REVIEW,
        icon: <FileSearchOutlined />,
        label: '统一审核中心',
        permission: 'hitl:view',
      },
    ],
  },
  {
    group: '组织与权限',
    items: [
      { path: ROUTE_PATH.ORGANIZATION, icon: <TeamOutlined />, label: '组织与用户', permission: 'tenant:manage' },
      { path: ROUTE_PATH.ROLE, icon: <KeyOutlined />, label: '角色管理', permission: 'tenant:manage' },
      { path: ROUTE_PATH.TENANT, icon: <BankOutlined />, label: '租户管理', permission: 'tenant:manage' },
    ],
  },
];

/** 模式默认页面 */
const MODE_DEFAULT_PAGE: Record<SidebarMode, string> = {
  user: ROUTE_PATH.WORKBENCH,
  admin: ROUTE_PATH.ADMIN_OBSERVE,
};

/** 判断路径属于哪个模式 */
function inferMode(pathname: string): SidebarMode {
  if (
    pathname.startsWith('/admin') ||
    pathname.startsWith('/model') ||
    pathname.startsWith('/audit') ||
    pathname.startsWith('/security') ||
    pathname.startsWith('/review') ||
    pathname.startsWith('/tenant') ||
    pathname.startsWith('/organization') ||
    pathname.startsWith('/role') ||
    pathname.startsWith('/profile') ||
    pathname.startsWith('/monitor')
  ) {
    return 'admin';
  }
  return 'user';
}

interface SidebarProps {
  collapsed: boolean;
}

export const Sidebar: React.FC<SidebarProps> = ({ collapsed }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState<SidebarMode>(inferMode(location.pathname));
  const { hasPermission } = usePermission();

  const hasAnyAdminPermission =
    hasPermission('observe:view') ||
    hasPermission('security:policy:view') ||
    hasPermission('security:audit:view') ||
    hasPermission('hitl:view') ||
    hasPermission('tenant:manage') ||
    hasPermission('model:view');

  const modes: SidebarMode[] = hasAnyAdminPermission ? ['user', 'admin'] : ['user'];

  const menus = useMemo(() => {
    if (mode === 'user') return USER_MENUS;
    return ADMIN_MENUS.map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || hasPermission(item.permission)),
    })).filter((group) => group.items.length > 0);
  }, [mode, hasPermission]);

  const handleModeSwitch = (newMode: SidebarMode) => {
    if (newMode === mode) return;
    setMode(newMode);
    navigate(MODE_DEFAULT_PAGE[newMode]);
  };

  const handleMenuClick = (path: string) => {
    navigate(path);
  };

  return (
    <Sider
      collapsible
      collapsed={collapsed}
      trigger={null}
      width={240}
      collapsedWidth={64}
      style={{
        overflow: 'auto',
        height: '100vh',
        position: 'sticky',
        top: 0,
        left: 0,
        background: '#111827',
      }}
    >
      {/* Logo 区 */}
      <div
        style={{
          height: 60,
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '0 20px',
          borderBottom: '1px solid #1f2937',
        }}
      >
        <div
          style={{
            width: 32,
            height: 32,
            minWidth: 32,
            background: 'linear-gradient(135deg, #4f46e5, #7c3aed)',
            borderRadius: 8,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 700,
            fontSize: 16,
          }}
        >
          A
        </div>
        {!collapsed && (
          <span style={{ color: '#fff', fontWeight: 600, fontSize: 15, whiteSpace: 'nowrap' }}>
            Aegis Agent
          </span>
        )}
      </div>

      {/* 模式切换 */}
      {!collapsed && (
        <div style={{ padding: '12px 16px', borderBottom: '1px solid #1f2937' }}>
          <div style={{ display: 'flex', background: '#1f2937', borderRadius: 8, padding: 3 }}>
            {modes.map((m) => (
              <div
                key={m}
                onClick={() => handleModeSwitch(m)}
                style={{
                  flex: 1,
                  padding: '8px 4px',
                  textAlign: 'center',
                  borderRadius: 6,
                  fontSize: 13,
                  cursor: 'pointer',
                  transition: 'all .2s',
                  background: mode === m ? '#4f46e5' : 'transparent',
                  color: mode === m ? '#fff' : '#9ca3af',
                  fontWeight: mode === m ? 500 : 400,
                  whiteSpace: 'nowrap',
                }}
              >
                {m === 'user' ? '员工工作台' : '管理控制台'}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 菜单列表 */}
      <nav style={{ padding: '8px 0' }}>
        {menus.map((group) => (
          <div key={group.group}>
            {!collapsed && (
              <div
                style={{
                  padding: '12px 20px 6px',
                  fontSize: 11,
                  color: '#6b7280',
                  textTransform: 'uppercase',
                  letterSpacing: 1,
                }}
              >
                {group.group}
              </div>
            )}
            {group.items.map((item) => {
              const active = location.pathname === item.path;
              return (
                <div
                  key={item.path}
                  onClick={() => handleMenuClick(item.path)}
                  title={collapsed ? item.label : undefined}
                  style={{
                    padding: collapsed ? '10px 0' : '10px 20px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    cursor: 'pointer',
                    fontSize: 14,
                    color: active ? '#fff' : '#d1d5db',
                    background: active ? '#1f2937' : 'transparent',
                    borderLeft: active ? '3px solid #4f46e5' : '3px solid transparent',
                    justifyContent: collapsed ? 'center' : 'flex-start',
                    transition: 'all .15s',
                    position: 'relative',
                  }}
                  onMouseEnter={(e) => {
                    if (!active) e.currentTarget.style.background = '#1f2937';
                  }}
                  onMouseLeave={(e) => {
                    if (!active) e.currentTarget.style.background = 'transparent';
                  }}
                >
                  <span style={{ fontSize: 16, display: 'flex', alignItems: 'center' }}>
                    {item.icon}
                  </span>
                  {!collapsed && <span>{item.label}</span>}
                  {!collapsed && item.badge && (
                    <span
                      style={{
                        marginLeft: 'auto',
                        background: '#ef4444',
                        color: '#fff',
                        fontSize: 10,
                        padding: '1px 6px',
                        borderRadius: 10,
                        fontWeight: 600,
                      }}
                    >
                      {item.badge}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </nav>
    </Sider>
  );
};

export default Sidebar;
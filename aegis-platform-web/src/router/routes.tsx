/**
 * @file 路由配置
 * @description 平台路由定义：登录页、主布局子路由（由 AuthGuard 保护），404 兜底
 * @author wang.zhen
 * @since 1.0.0
 */
import { lazy } from 'react';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import type { RouteObject } from 'react-router-dom';
import { AuthGuard } from './AuthGuard';
import { MainLayout } from '@/components/layout/MainLayout';
import { ROUTE_PATH } from '@/utils/constants';

const Login = lazy(() => import('@/pages/login'));
const Workbench = lazy(() => import('@/pages/workbench'));
const AgentList = lazy(() => import('@/pages/agent/list'));
const AgentDetail = lazy(() => import('@/pages/agent/detail'));
const AgentCreate = lazy(() => import('@/pages/agent/create'));
const AgentEdit = lazy(() => import('@/pages/agent/edit'));
const SkillPage = lazy(() => import('@/pages/resource/skill'));
const KnowledgePage = lazy(() => import('@/pages/resource/knowledge'));
const McpPage = lazy(() => import('@/pages/resource/mcp'));
const ToolPage = lazy(() => import('@/pages/resource/tool'));
const ModelPage = lazy(() => import('@/pages/model'));
const AuditPage = lazy(() => import('@/pages/audit'));
const SecurityPage = lazy(() => import('@/pages/security'));
const ReviewPage = lazy(() => import('@/pages/review'));
const TenantPage = lazy(() => import('@/pages/tenant'));
const OrganizationPage = lazy(() => import('@/pages/organization'));
const RolePage = lazy(() => import('@/pages/role'));
const ProfilePage = lazy(() => import('@/pages/profile'));
const AdminSandbox = lazy(() => import('@/pages/admin/sandbox'));
const AdminHA = lazy(() => import('@/pages/admin/ha'));
const AdminObserve = lazy(() => import('@/pages/admin/observe'));
const NotFound = lazy(() => import('@/pages/not-found'));
const RouteErrorPage = lazy(() => import('@/pages/route-error'));

/** 由 AuthGuard 包裹页面 */
const withGuard = (node: ReactNode, permissions?: string[]) => (
  <AuthGuard requiredPermissions={permissions}>{node}</AuthGuard>
);

export const routes: RouteObject[] = [
  { path: ROUTE_PATH.LOGIN, element: <Login /> },
  {
    path: '/',
    element: <MainLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <Navigate to={ROUTE_PATH.WORKBENCH} replace /> },
      // ===== 员工工作台 =====
      { path: ROUTE_PATH.WORKBENCH, element: withGuard(<Workbench />) },
      // P1-2：对话页已合并入工作台，/chat 重定向到 /workbench
      { path: ROUTE_PATH.CHAT, element: <Navigate to={ROUTE_PATH.WORKBENCH} replace /> },
      { path: ROUTE_PATH.AGENT_LIST, element: withGuard(<AgentList />) },
      { path: ROUTE_PATH.AGENT_DETAIL, element: withGuard(<AgentDetail />) },
      { path: ROUTE_PATH.AGENT_CREATE, element: withGuard(<AgentCreate />) },
      { path: ROUTE_PATH.AGENT_EDIT, element: withGuard(<AgentEdit />) },
      { path: ROUTE_PATH.RESOURCE_SKILL, element: withGuard(<SkillPage />) },
      { path: ROUTE_PATH.RESOURCE_KNOWLEDGE, element: withGuard(<KnowledgePage />) },
      { path: ROUTE_PATH.RESOURCE_MCP, element: withGuard(<McpPage />) },
      { path: ROUTE_PATH.RESOURCE_TOOL, element: withGuard(<ToolPage />) },
      // ===== 管理控制台 =====
      {
        path: ROUTE_PATH.ADMIN_SANDBOX,
        element: withGuard(<AdminSandbox />, ['sandbox:view']),
      },
      { path: ROUTE_PATH.MODEL, element: withGuard(<ModelPage />, ['model:view']) },
      { path: ROUTE_PATH.SECURITY, element: withGuard(<SecurityPage />, ['security:policy:view']) },
      {
        path: ROUTE_PATH.ADMIN_OBSERVE,
        element: withGuard(<AdminObserve />, ['observe:view']),
      },
      { path: ROUTE_PATH.AUDIT, element: withGuard(<AuditPage />, ['security:audit:view']) },
      { path: ROUTE_PATH.REVIEW, element: withGuard(<ReviewPage />, ['hitl:view']) },
      {
        path: ROUTE_PATH.ADMIN_HA,
        element: withGuard(<AdminHA />, ['model:view']),
      },
      { path: ROUTE_PATH.TENANT, element: withGuard(<TenantPage />, ['tenant:manage']) },
      {
        path: ROUTE_PATH.ORGANIZATION,
        element: withGuard(<OrganizationPage />, ['tenant:manage']),
      },
      { path: ROUTE_PATH.ROLE, element: withGuard(<RolePage />, ['tenant:manage']) },
      { path: ROUTE_PATH.PROFILE, element: withGuard(<ProfilePage />) },
    ],
  },
  { path: '*', element: <NotFound /> },
];
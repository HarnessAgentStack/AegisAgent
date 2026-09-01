/**
 * @file 租户上下文状态管理
 * @description 当前租户切换、可访问租户列表、租户上下文持久化
 * @author wang.zhen
 * @since 1.0.0
 */
import { create } from 'zustand';
import type { Tenant, TenantContext } from '@/types/tenant';
import { storage } from '@/utils/storage';
import { STORAGE_KEY } from '@/utils/constants';

/** 租户状态 */
interface TenantState {
  /** 当前租户 ID */
  currentTenantId: string | null;
  /** 当前租户 */
  currentTenant: Tenant | null;
  /** 可访问租户列表（用户可切换的租户） */
  tenants: Tenant[];
  /** 设置当前租户 */
  setCurrentTenant: (tenant: Tenant) => void;
  /** 设置可访问租户列表，并自动选中首个或已持久化的租户 */
  setTenants: (tenants: Tenant[]) => void;
  /** 切换租户 */
  switchTenant: (tenantId: string) => void;
  /** 获取当前租户上下文 */
  getTenantContext: () => TenantContext | null;
  /** 清空租户上下文 */
  clear: () => void;
}

const initialTenantId = storage.get<string | null>(STORAGE_KEY.TENANT_ID, null);

export const useTenantStore = create<TenantState>((set, get) => ({
  currentTenantId: initialTenantId,
  currentTenant: null,
  tenants: [],

  setCurrentTenant: (tenant) => {
    storage.set(STORAGE_KEY.TENANT_ID, tenant.id);
    set({ currentTenantId: tenant.id, currentTenant: tenant });
  },

  setTenants: (tenants) => {
    const { currentTenantId } = get();
    const current =
      tenants.find((t) => t.id === currentTenantId) ?? tenants[0] ?? null;
    set({ tenants, currentTenant: current, currentTenantId: current?.id ?? null });
  },

  switchTenant: (tenantId) => {
    const tenant = get().tenants.find((t) => t.id === tenantId);
    if (tenant) {
      storage.set(STORAGE_KEY.TENANT_ID, tenant.id);
      set({ currentTenantId: tenant.id, currentTenant: tenant });
    }
  },

  getTenantContext: () => {
    const { currentTenant } = get();
    if (!currentTenant) return null;
    return {
      tenantId: currentTenant.id,
      tenantCode: currentTenant.tenantCode,
      tenantName: currentTenant.tenantName,
    };
  },

  clear: () => {
    storage.remove(STORAGE_KEY.TENANT_ID);
    set({ currentTenantId: null, currentTenant: null, tenants: [] });
  },
}));
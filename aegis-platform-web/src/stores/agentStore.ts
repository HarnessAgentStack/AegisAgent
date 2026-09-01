/**
 * @file 智能体状态管理
 * @description 当前智能体切换、最近使用智能体、智能体列表缓存
 * @author wang.zhen
 * @since 1.0.0
 */
import { create } from 'zustand';
import type { Agent } from '@/types/agent';
import { storage } from '@/utils/storage';
import { STORAGE_KEY } from '@/utils/constants';

/** 智能体状态 */
interface AgentState {
  /** 当前选中的智能体 */
  currentAgent: Agent | null;
  /** 最近使用智能体 ID 列表（按时间倒序，最多 10 个） */
  recentAgentIds: string[];
  /** 智能体列表缓存（市场 / 我的） */
  agents: Agent[];
  /** 设置当前智能体 */
  setCurrentAgent: (agent: Agent) => void;
  /** 设置智能体列表 */
  setAgents: (agents: Agent[]) => void;
  /** 记录最近使用 */
  recordRecent: (agentId: string) => void;
  /** 清空 */
  clear: () => void;
}

/** 恢复最近使用智能体 ID 列表（兼容旧版单 ID 存储） */
const restored = storage.get<string | string[]>(STORAGE_KEY.AGENT_ID, []);
const initialRecentIds: string[] = Array.isArray(restored)
  ? restored
  : typeof restored === 'string'
    ? [restored]
    : [];

export const useAgentStore = create<AgentState>((set, get) => ({
  currentAgent: null,
  recentAgentIds: initialRecentIds,
  agents: [],

  setCurrentAgent: (agent) => set({ currentAgent: agent }),

  setAgents: (agents) => set({ agents }),

  recordRecent: (agentId) => {
    const ids = [agentId, ...get().recentAgentIds.filter((id) => id !== agentId)].slice(0, 10);
    storage.set(STORAGE_KEY.AGENT_ID, ids);
    set({ recentAgentIds: ids });
  },

  clear: () => {
    storage.remove(STORAGE_KEY.AGENT_ID);
    set({ currentAgent: null, recentAgentIds: [], agents: [] });
  },
}));
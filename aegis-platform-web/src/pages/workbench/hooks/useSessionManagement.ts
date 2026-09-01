/**
 * @file 会话管理 Hook
 * @description 加载会话列表、切换会话、删除会话
 * @author wang.zhen
 * @since 1.0.0
 */
import { useCallback, useEffect, useState } from 'react';
import { getSessionList, getMessages, deleteSession as deleteSessionApi } from '@/api/session';
import type { Session } from '@/types/session';
import type { SessionItem } from '../utils';

interface UseSessionManagementOptions {
  currentAgentId: string | undefined;
}

export function useSessionManagement({ currentAgentId }: UseSessionManagementOptions) {
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | undefined>();

  const loadSessions = useCallback(async () => {
    if (!currentAgentId) return;
    try {
      const result = await getSessionList({ page: 1, size: 20, agentId: currentAgentId });
      const items: SessionItem[] = (result.sessions || []).map((s: Session) => ({
        id: s.sessionId || s.id,
        title: s.title || s.agentName || '新对话',
        time: (s.lastActiveAt || s.lastActiveTime)
          ? new Date(s.lastActiveAt || s.lastActiveTime as string).toLocaleString('zh-CN', {
              month: '2-digit',
              day: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
            })
          : '刚刚',
      }));
      setSessions(items);
    } catch (err) {
      console.error(err);
    }
  }, [currentAgentId]);

  useEffect(() => {
    loadSessions();
  }, [loadSessions]);

  /** 切换会话 —— 直接加载消息，无需 streaming 检查（调用方自行处理中断） */
  const switchSession = useCallback(async (sessionId: string) => {
    setCurrentSessionId(sessionId);
    try {
      const msgs = await getMessages(sessionId);
      return msgs;
    } catch (err) {
      console.error(err);
      return [];
    }
  }, []);

  /** 删除会话 */
  const deleteSessionItem = useCallback(async (sessionId: string) => {
    await deleteSessionApi(sessionId);
    await loadSessions();
  }, [loadSessions]);

  /** 新建（重置）会话状态 */
  const resetSession = useCallback(() => {
    setCurrentSessionId(undefined);
  }, []);

  return {
    sessions,
    currentSessionId,
    setCurrentSessionId,
    loadSessions,
    switchSession,
    deleteSessionItem,
    resetSession,
  };
}

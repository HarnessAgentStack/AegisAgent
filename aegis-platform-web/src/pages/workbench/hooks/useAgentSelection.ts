/**
 * @file 智能体选择 Hook
 * @description 加载智能体列表（通用 + 我的 + 订阅）、切换智能体
 * @author wang.zhen
 * @since 1.0.0
 */
import { useCallback, useEffect, useState } from 'react';
import { getSubscribableAgents, getMyAgents, getUniversalAgent } from '@/api/agent';
import type { Agent } from '@/types/agent';
import { AgentType } from '@/types/enum';

export function useAgentSelection() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [currentAgentId, setCurrentAgentId] = useState<string | undefined>();

  const loadAgents = useCallback(async () => {
    setAgentsLoading(true);
    try {
      const [universalAgent, myAgents, subscribableAgents] = await Promise.all([
        getUniversalAgent(),
        getMyAgents(),
        getSubscribableAgents(),
      ]);
      const allIds = new Set<string>();
      const result: Agent[] = [];

      if (universalAgent) {
        result.push(universalAgent);
        allIds.add(universalAgent.id);
      }

      for (const agent of myAgents) {
        if (!allIds.has(agent.id)) {
          result.push(agent);
          allIds.add(agent.id);
        }
      }

      for (const agent of subscribableAgents) {
        if (agent.agentType === AgentType.SYSTEM) continue;
        if (!allIds.has(agent.id)) {
          result.push(agent);
          allIds.add(agent.id);
        }
      }

      setAgents(result);
      const universal = result.find((a) => a.agentType === AgentType.UNIVERSAL);
      setCurrentAgentId(universal?.id ?? result[0]?.id);
    } catch (err) {
      console.error(err);
    } finally {
      setAgentsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAgents();
  }, [loadAgents]);

  const currentAgent = agents.find((a) => a.id === currentAgentId);
  const isUniversal = currentAgent?.agentType === AgentType.UNIVERSAL;

  return {
    agents,
    agentsLoading,
    currentAgentId,
    setCurrentAgentId,
    setAgents,
    currentAgent,
    isUniversal,
    loadAgents,
  };
}

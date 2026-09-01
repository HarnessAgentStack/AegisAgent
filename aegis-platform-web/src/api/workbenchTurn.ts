/**
 * @file 工作台轮次精重建（基于 observe 会话详情）
 * @description 当 admin/observe 域会话详情接口对当前用户可用时，用 rounds[].steps[] 的
 *              精确 startTime 把历史轮次重建为严格时序交错的 events，弥补
 *              getMessages 轻量重建（reasoning 无法拆段）的精度不足（P2-2）。
 *
 * 设计：
 *   - 优先级低于 getMessages，作为"可选增强"：调用方先 getMessages，再尝试
 *     enrichTurnsFromObserve 覆盖；若 observe 不可用（403/网络错）静默回退。
 *   - 不直接侵入 useWorkbenchChat；由历史切换流程按需调用。
 *
 * @author Aegis
 * @since 4.2.0
 */
import { getSessionDetail, type RoundDetail, type StepDetail } from '@/api/observe';
import type { Message } from '@/types/session';
import { MessageRole } from '@/types/enum';
import { genTurnEventId, type TurnEvent, type TurnEventStatus, type TurnMeta } from '@/types/turn';
import { safeJsonParse } from '@/utils/number';

/** 把单个 StepDetail 映射为 1~2 个 TurnEvent（LLM_CALL → thinking+answer；TOOL_CALL → tool） */
function stepToEvents(step: StepDetail): TurnEvent[] {
  const ts = step.startTime ? new Date(step.startTime).getTime() : Date.now();
  const status: TurnEventStatus = step.status === 'FAILED' ? 'FAILED' : step.status === 'RUNNING' ? 'RUNNING' : 'SUCCESS';

  if (step.spanType === 'LLM_CALL') {
    const events: TurnEvent[] = [];
    if (step.reasoningPreview || step.responseText) {
      // 思考段（reasoningPreview 优先）
      if (step.reasoningPreview) {
        events.push({
          id: genTurnEventId(),
          kind: 'thinking',
          timestamp: ts,
          payload: {
            title: `LLM 推理${step.modelName ? ` · ${step.modelName}` : ''}`,
            detail: step.reasoningPreview,
            status: 'SUCCESS',
            durationMs: step.durationMs,
          },
        });
      }
    }
    if (step.responseText) {
      events.push({
        id: genTurnEventId(),
        kind: 'answer',
        timestamp: ts + 1,
        payload: { text: step.responseText },
      });
    }
    return events;
  }

  if (step.spanType === 'TOOL_CALL') {
    const args = step.toolArguments ?? (step.toolArgumentsJson ? safeJsonParse<Record<string, unknown>>(step.toolArgumentsJson) ?? undefined : undefined);
    return [
      {
        id: genTurnEventId(),
        kind: 'tool',
        timestamp: ts,
        payload: {
          toolId: step.toolCallId ?? genTurnEventId(),
          toolName: step.displayName || step.name,
          arguments: args,
          result: step.toolResult,
          status,
          durationMs: step.durationMs,
        },
      },
    ];
  }

  // 其他 span 类型（RAG/HITL/SANDBOX）暂不进事件流
  return [];
}

/** 轮次 → events（所有 steps 展开 + 按 timestamp 排序） */
function roundToEvents(round: RoundDetail): TurnEvent[] {
  const events: TurnEvent[] = [];
  for (const step of round.steps) {
    events.push(...stepToEvents(step));
  }
  return events.sort((a, b) => a.timestamp - b.timestamp);
}

/**
 * 用 observe 会话详情富化历史 messages 的 events/turnMeta。
 * 失败时返回原数组（静默回退）。
 */
export async function enrichTurnsFromObserve(
  sessionId: string,
  messages: Message[],
): Promise<Message[]> {
  try {
    const detail = await getSessionDetail(sessionId);
    if (!detail?.rounds || detail.rounds.length === 0) return messages;

    // 建立 roundIndex → events 映射；按顺序匹配 messages 中的 assistant 轮次
    let roundCursor = 0;
    const enriched = messages.map((m) => {
      if (m.role !== MessageRole.ASSISTANT) return m;
      // 跳过非展示轮（USER_INPUT 等）
      while (roundCursor < detail.rounds.length) {
        const round = detail.rounds[roundCursor];
        roundCursor++;
        if (round.roundType === 'USER_INPUT' || round.roundType === 'USER_QUERY') continue;
        const events = roundToEvents(round);
        const meta: TurnMeta = {
          model: round.steps.find((s) => s.spanType === 'LLM_CALL')?.modelName,
          durationMs: round.durationMs,
          tokenIn: round.tokenInput,
          tokenOut: round.tokenOutput,
          stepCount: round.steps.length,
          isComplete: true,
        };
        // answerText 派生回 content（若 observe 有 responseText 则覆盖）
        const answerText = events
          .filter((e) => e.kind === 'answer')
          .map((e) => (e.payload as { text: string }).text)
          .join('');
        return {
          ...m,
          events,
          turnMeta: meta,
          content: answerText || m.content,
        };
      }
      return m;
    });
    return enriched;
  } catch (err) {
    console.warn('[enrichTurnsFromObserve] observe 不可用，回退轻量重建:', err);
    return messages;
  }
}

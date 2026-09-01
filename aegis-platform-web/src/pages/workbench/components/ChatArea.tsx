/**
 * @file 聊天消息渲染区（4.0 重构版）
 * @description 按 UserMessage / SystemNotice / AssistantTurn 三分渲染，移除旧
 *              "最后一条 AI 消息挂独立 ExecutionTimeline + 历史走退化路径"的双形态。
 *              折叠策略(精简模式/thinkingStyle/collapsedTools)由顶层注入，默认 collapsedPreview/all。
 *
 * @author wang.zhen
 * @since 4.0.0
 */
import React, { useEffect } from 'react';
import type { Message } from '@/types/session';
import { MessageRole } from '@/types/enum';
import type { CollapsePolicy } from '@/types/collapsePolicy';
import { DEFAULT_COLLAPSE_POLICY } from '@/types/collapsePolicy';
import UserMessage from './turn/UserMessage';
import SystemNotice from './turn/systemNotice';
import AssistantTurn from './turn/AssistantTurn';

interface ChatAreaProps {
  messages: Message[];
  streaming: boolean;
  markdownStyles: string;
  /** 折叠策略（缺省默认值） */
  policy?: CollapsePolicy;
  /** 用户信息 */
  userNickname?: string;
  /** AI 名称 */
  agentName?: string;
  onCopy?: (text: string) => void;
  onDeleteMessage?: (sessionId: string, messageId: string) => Promise<void>;
  onApproveHitl?: () => void;
  onRejectHitl?: () => void;
  onResumeFromConflict?: (sessionId: string) => Promise<void>;
  /** 重新生成 AI 消息 */
  onRegenerate?: (messageId: string) => void;
  /** 编辑用户消息 */
  onEditMessage?: (messageId: string, newText: string) => void;
}

export const ChatArea: React.FC<ChatAreaProps> = ({
  messages,
  streaming,
  markdownStyles,
  policy = DEFAULT_COLLAPSE_POLICY,
  userNickname,
  agentName,
  onCopy,
  onDeleteMessage,
  onApproveHitl,
  onRejectHitl,
  onRegenerate,
  onResumeFromConflict,
  onEditMessage,
}) => {
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // 找最后一条 assistant（用于"重新生成"可用性）
  let lastAssistantId: string | undefined;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === MessageRole.ASSISTANT) {
      lastAssistantId = messages[i].id;
      break;
    }
  }

  return (
    <>
      {messages.map((msg, idx) => {
        // 用户消息
        if (msg.role === MessageRole.USER) {
          const nextIsUser = idx + 1 < messages.length && messages[idx + 1].role === MessageRole.USER;
          return (
            <UserMessage
              key={msg.id}
              message={msg}
              userNickname={userNickname}
              canEdit={!streaming && !nextIsUser}
              onEdit={onEditMessage}
              onDelete={onDeleteMessage}
            />
          );
        }
        // 系统消息（技能激活/KB 跳过 等）
        if (msg.role === MessageRole.SYSTEM) {
          return <SystemNotice key={msg.id} message={msg} />;
        }
        // 助手轮次
        if (msg.role === MessageRole.ASSISTANT) {
          const isLast = msg.id === lastAssistantId;
          return (
            <AssistantTurn
              key={msg.id}
              message={msg}
              agentName={agentName}
              streaming={streaming}
              policy={policy}
              markdownStyles={markdownStyles}
              canRegenerate={isLast && !streaming}
              onCopy={onCopy}
              onApproveHitl={onApproveHitl}
              onRejectHitl={onRejectHitl}
              onRegenerate={onRegenerate}
              onResumeFromConflict={onResumeFromConflict}
            />
          );
        }
        // tool role（旧数据兜底，合并进前一条 assistant）
        return null;
      })}
      <div ref={messagesEndRef} />
    </>
  );
};

export default ChatArea;

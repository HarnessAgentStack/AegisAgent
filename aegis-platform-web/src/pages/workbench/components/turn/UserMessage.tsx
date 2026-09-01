/**
 * @file 用户消息项
 * @description 右对齐紫底气泡 + 头像 + 昵称 + 时间戳 + 资源上下文回显 chip 行
 *              (KB/MCP/附件/Skill，来自 Message.context 快照) + 操作栏(编辑/复制)。
 *
 * @author Aegis
 * @since 4.1.0
 */
import React, { useState } from 'react';
import { Avatar, Button, Input, Tag } from 'antd';
import { EditOutlined, DeleteOutlined, CheckOutlined } from '@ant-design/icons';
import type { Message } from '@/types/session';
import type { MessageContext } from '@/types/turn';
import { formatDateTime } from '@/utils/format';

interface UserMessageProps {
  message: Message;
  userNickname?: string;
  /** 是否最后一条用户消息（编辑入口可用性） */
  canEdit?: boolean;
  onEdit?: (messageId: string, newText: string) => void;
  onDelete?: (sessionId: string, messageId: string) => void;
}

/** 资源上下文回显 chip 行 */
const ContextChips: React.FC<{ ctx?: MessageContext }> = ({ ctx }) => {
  if (!ctx) return null;
  const hasAny =
    (ctx.kbIds && ctx.kbIds.length > 0) ||
    (ctx.mcpIds && ctx.mcpIds.length > 0) ||
    (ctx.attachments && ctx.attachments.length > 0) ||
    (ctx.skills && ctx.skills.length > 0);
  if (!hasAny) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6, justifyContent: 'flex-end' }}>
      {ctx.kbIds?.map((id) => (
        <Tag key={`kb-${id}`} color="blue" style={{ margin: 0, fontSize: 10 }}>
          📚 {id}
        </Tag>
      ))}
      {ctx.mcpIds?.map((id) => (
        <Tag key={`mcp-${id}`} color="cyan" style={{ margin: 0, fontSize: 10 }}>
          🔌 {id}
        </Tag>
      ))}
      {ctx.attachments?.map((a, i) => (
        <Tag key={`att-${i}`} color="purple" style={{ margin: 0, fontSize: 10 }}>
          📎 {a.fileName || a.name || '附件'}
        </Tag>
      ))}
      {ctx.skills?.map((code) => (
        <Tag key={`sk-${code}`} color="gold" style={{ margin: 0, fontSize: 10 }}>
          ⚡ {code}
        </Tag>
      ))}
    </div>
  );
};

export const UserMessage: React.FC<UserMessageProps> = ({ message, userNickname, canEdit, onEdit, onDelete }) => {
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState('');

  const ts = message.createdAt ? formatDateTime(message.createdAt, 'HH:mm') : '';

  return (
    <div style={{ display: 'flex', gap: 10, marginBottom: 20, flexDirection: 'row-reverse' }}>
      <Avatar size={32} style={{ background: '#4f46e5', minWidth: 32, flexShrink: 0 }}>
        {userNickname?.[0] ?? '我'}
      </Avatar>
      <div style={{ maxWidth: '78%', minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2, justifyContent: 'flex-end' }}>
          <span style={{ fontSize: 11, color: 'var(--color-text-tertiary)' }}>{ts}</span>
          <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-text-secondary)' }}>{userNickname ?? '我'}</span>
        </div>

        {/* 资源上下文回显 */}
        <ContextChips ctx={message.context} />

        {editing ? (
          <div style={{ background: 'var(--color-bg-chat-user)', borderRadius: 12, padding: 10 }}>
            <Input.TextArea value={editText} onChange={(e) => setEditText(e.target.value)} autoSize={{ minRows: 2, maxRows: 8 }} style={{ marginBottom: 8 }} />
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button size="small" onClick={() => { setEditing(false); setEditText(''); }}>取消</Button>
              <Button
                size="small"
                type="primary"
                icon={<CheckOutlined />}
                onClick={() => {
                  if (!editText.trim()) return;
                  setEditing(false);
                  onEdit?.(message.id, editText);
                }}
              >
                保存并重生成
              </Button>
            </div>
          </div>
        ) : (
          <div
            style={{
              background: 'var(--color-bg-chat-user)',
              padding: '10px 16px',
              borderRadius: 12,
              fontSize: 14,
              lineHeight: 1.7,
              color: 'var(--color-text-on-assistant)',
            }}
          >
            <span style={{ whiteSpace: 'pre-wrap' }}>{message.content}</span>
          </div>
        )}

        {/* 操作栏 */}
        <div
          style={{ marginTop: 4, display: 'flex', gap: 4, opacity: 0.6, transition: 'opacity 0.15s', justifyContent: 'flex-end' }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.opacity = '1'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.opacity = '0.6'; }}
        >
          {canEdit && onEdit && !editing && (
            <Button
              size="small"
              type="text"
              icon={<EditOutlined />}
              title="编辑"
              onClick={() => {
                setEditing(true);
                setEditText(message.content ?? '');
              }}
            />
          )}
          {onDelete && (
            <Button
              size="small"
              type="text"
              icon={<DeleteOutlined />}
              title="删除"
              onClick={() => {
                onDelete(message.sessionId || '', message.id);
              }}
            />
          )}
        </div>
      </div>
    </div>
  );
};

export default UserMessage;

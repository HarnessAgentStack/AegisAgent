/**
 * @file 系统消息项（技能激活/KB 跳过 等）
 * @description 居中淡色 pill 展示 system role 消息。
 *
 * @author Aegis
 * @since 4.1.0
 */
import React from 'react';
import type { Message } from '@/types/session';

interface SystemNoticeProps {
  message: Message;
}

export const SystemNotice: React.FC<SystemNoticeProps> = ({ message }) => {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}>
      <div
        style={{
          padding: '4px 12px',
          background: '#f0f0f0',
          borderRadius: 12,
          fontSize: 12,
          color: 'var(--color-text-tertiary)',
          maxWidth: '80%',
          textAlign: 'center',
        }}
      >
        {message.content}
      </div>
    </div>
  );
};

export default SystemNotice;

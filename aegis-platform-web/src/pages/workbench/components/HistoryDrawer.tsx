/**
 * @file 历史会话抽屉
 * @description 从 Workbench 抽取的 Drawer 组件，展示会话列表 + 切换 + 删除
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Drawer, Button, Modal, App } from 'antd';
import { PlusOutlined, DeleteOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import type { SessionItem } from '../utils';

interface HistoryDrawerProps {
  open: boolean;
  sessions: SessionItem[];
  currentSessionId?: string;
  streaming: boolean;
  onClose: () => void;
  onNewTask: () => void;
  onSwitch: (sessionId: string) => void;
  onDeleteSession: (sessionId: string) => Promise<void>;
  loadSessions: () => void;
  onCurrentSessionDeleted?: () => void;
  abortStream: () => void;
}

export const HistoryDrawer: React.FC<HistoryDrawerProps> = ({
  open, sessions, currentSessionId, streaming, onClose, onNewTask, onSwitch,
  onDeleteSession, loadSessions, onCurrentSessionDeleted, abortStream,
}) => {
  const { message } = App.useApp();

  const handleDelete = (e: React.MouseEvent, s: SessionItem) => {
    e.stopPropagation();
    Modal.confirm({
      title: '确认删除会话？',
      icon: <ExclamationCircleOutlined />,
      content: '删除后无法恢复，会话中的所有消息将一并删除。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await onDeleteSession(s.id);
          message.success('会话已删除');
          if (s.id === currentSessionId) {
            onCurrentSessionDeleted?.();
          }
          loadSessions();
        } catch (err) {
          message.error(`删除失败：${(err as Error)?.message || '服务异常'}`);
        }
      },
    });
  };

  const handleSwitch = (sessionId: string) => {
    if (streaming) {
      Modal.confirm({
        title: '确认切换会话？',
        icon: <ExclamationCircleOutlined />,
        content: '当前正在生成回复，切换会话将中断生成。',
        okText: '确认切换',
        cancelText: '继续等待',
        onOk: () => {
          abortStream();
          onSwitch(sessionId);
          onClose();
        },
      });
    } else {
      onSwitch(sessionId);
      onClose();
    }
  };

  return (
    <Drawer
      title="任务历史"
      placement="right"
      width={320}
      open={open}
      onClose={onClose}
      styles={{ body: { padding: '8px 12px' } }}
      extra={
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => {
          onNewTask();
          onClose();
        }}>
          新建
        </Button>
      }
    >
      {sessions.length === 0 ? (
        <div style={{ padding: 40, textAlign: 'center', color: '#9ca3af', fontSize: 13 }}>
          暂无任务历史
        </div>
      ) : (
        sessions.map((s) => (
          <div
            key={s.id}
            data-testid={`session-${s.id}`}
            onClick={() => handleSwitch(s.id)}
            style={{
              padding: '12px 16px', borderRadius: 8, cursor: 'pointer',
              marginBottom: 4, transition: 'background .15s',
              background: s.id === currentSessionId ? '#eef2ff' : 'transparent',
              border: s.id === currentSessionId ? '1px solid #4f46e5' : '1px solid transparent',
              position: 'relative',
            }}
          >
            <div style={{
              fontSize: 13, fontWeight: 500,
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            }}>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                {s.title}
              </span>
              <DeleteOutlined
                style={{ fontSize: 12, color: '#d1d5db', flexShrink: 0, marginLeft: 8 }}
                onClick={(e) => handleDelete(e, s)}
              />
            </div>
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 4 }}>{s.time}</div>
          </div>
        ))
      )}
    </Drawer>
  );
};

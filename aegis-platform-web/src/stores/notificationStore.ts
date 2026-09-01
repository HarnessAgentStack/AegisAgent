/**
 * @file 通知状态管理
 * @description 站内通知列表、未读数、标记已读
 * @author wang.zhen
 * @since 1.0.0
 */
import { create } from 'zustand';

/** 通知类型 */
export type NotificationType = 'info' | 'success' | 'warning' | 'error';

/** 站内通知 */
export interface AppNotification {
  /** 通知 ID */
  id: string;
  /** 通知类型 */
  type: NotificationType;
  /** 标题 */
  title: string;
  /** 内容 */
  content?: string;
  /** 是否已读 */
  read: boolean;
  /** 创建时间 */
  createdAt: string;
  /** 跳转链接 */
  link?: string;
}

/** 通知状态 */
interface NotificationState {
  /** 通知列表 */
  notifications: AppNotification[];
  /** 未读数 */
  unreadCount: number;
  /** 设置通知列表 */
  setNotifications: (notifications: AppNotification[]) => void;
  /** 新增通知（置于列表头部） */
  addNotification: (notification: AppNotification) => void;
  /** 标记单条已读 */
  markAsRead: (id: string) => void;
  /** 全部标记已读 */
  markAllAsRead: () => void;
  /** 清空通知 */
  clear: () => void;
}

/** 计算未读数 */
const calcUnread = (list: AppNotification[]): number => list.filter((n) => !n.read).length;

export const useNotificationStore = create<NotificationState>((set, get) => ({
  notifications: [],
  unreadCount: 0,

  setNotifications: (notifications) =>
    set({ notifications, unreadCount: calcUnread(notifications) }),

  addNotification: (notification) => {
    const notifications = [notification, ...get().notifications];
    set({ notifications, unreadCount: calcUnread(notifications) });
  },

  markAsRead: (id) => {
    const notifications = get().notifications.map((n) =>
      n.id === id ? { ...n, read: true } : n,
    );
    set({ notifications, unreadCount: calcUnread(notifications) });
  },

  markAllAsRead: () => {
    const notifications = get().notifications.map((n) => ({ ...n, read: true }));
    set({ notifications, unreadCount: 0 });
  },

  clear: () => set({ notifications: [], unreadCount: 0 }),
}));
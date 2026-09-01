/**
 * @file 增强版输入框组件
 * @description 简化版输入框，功能按钮集成在输入框内：
 *   - 左侧：附件/技能/资源 功能按钮
 *   - 中间：文本输入区
 *   - 右下角：发送/停止按钮
 *   - 支持自适应高度、快捷键、历史记录
 * @author Aegis
 * @since 3.0.0
 */
import React, { useState, useRef, useEffect, useCallback, KeyboardEvent } from 'react';
import { Button, Tooltip } from 'antd';
import {
  SendOutlined,
  StopOutlined,
  PaperClipOutlined,
  ThunderboltOutlined,
  PlusOutlined,
} from '@ant-design/icons';

interface EnhancedMessageInputProps {
  /** 发送消息回调 */
  onSend: (content: string) => void;
  /** 停止生成回调 */
  onStop?: () => void;
  /** 清空回调（可选） */
  onClear?: () => void;
  /** 资源点击回调 */
  onResourceClick?: () => void;
  /** 附件点击回调 */
  onAttachmentClick?: () => void;
  /** 技能点击回调 */
  onSkillClick?: () => void;
  /** 加载状态 */
  loading?: boolean;
  /** 占位文本 */
  placeholder?: string;
  /** 是否禁用 */
  disabled?: boolean;
  /** 最大字符数 */
  maxLength?: number;
}

/**
 * 简化版增强输入框。
 * 功能：附件/技能/资源 入口 + 自适应高度 + 快捷键 + 发送/停止
 */
export const EnhancedMessageInput: React.FC<EnhancedMessageInputProps> = ({
  onSend,
  onStop,
  onClear,
  onResourceClick,
  onAttachmentClick,
  onSkillClick,
  loading = false,
  placeholder = '输入消息... (Enter 发送)',
  disabled = false,
  maxLength = 4000,
}) => {
  const [value, setValue] = useState('');
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /** 自适应高度 */
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      const scrollHeight = textareaRef.current.scrollHeight;
      textareaRef.current.style.height = Math.max(44, Math.min(scrollHeight, 160)) + 'px';
    }
  }, [value]);

  /** 发送消息 */
  const handleSend = useCallback(() => {
    const trimmedValue = value.trim();
    if (!trimmedValue || disabled || loading) return;
    onSend(trimmedValue);
    setHistory(prev => [trimmedValue, ...prev.slice(0, 49)]);
    setHistoryIndex(-1);
    setValue('');
    onClear?.();
  }, [value, disabled, loading, onSend, onClear]);

  /** 处理按键事件 */
  const handleKeyDown = useCallback((e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      handleSend();
      return;
    }
    // ↑ 历史记录
    if (e.key === 'ArrowUp' && value === '' && history.length > 0) {
      e.preventDefault();
      const newIndex = historyIndex < 0 ? history.length - 1 : Math.max(0, historyIndex - 1);
      setHistoryIndex(newIndex);
      setValue(history[newIndex]);
    }
    // ↓ 历史记录
    if (e.key === 'ArrowDown' && historyIndex >= 0) {
      e.preventDefault();
      const newIndex = historyIndex + 1;
      if (newIndex >= history.length) {
        setHistoryIndex(-1);
        setValue('');
      } else {
        setHistoryIndex(newIndex);
        setValue(history[newIndex]);
      }
    }
  }, [value, history, historyIndex, handleSend]);

  const charCountColor = value.length > maxLength * 0.9 ? '#ff4d4f' : '#bbb';

  return (
    <div style={{
      position: 'relative',
      background: '#fff',
      border: '1px solid #e0e0e0',
      borderRadius: 12,
      padding: '8px 10px',
      transition: 'border-color 0.2s',
      display: 'flex',
      flexDirection: 'column',
      gap: 6,
    }}>
      {/* 顶部工具栏：左侧功能按钮 + 右侧字符计数 */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}>
        {/* 左侧：功能入口 */}
        <div style={{ display: 'flex', gap: 2 }}>
          <Tooltip title="附件">
            <Button
              size="small"
              type="text"
              icon={<PaperClipOutlined />}
              onClick={onAttachmentClick}
              disabled={loading || disabled}
            />
          </Tooltip>
          <Tooltip title="技能">
            <Button
              size="small"
              type="text"
              icon={<ThunderboltOutlined />}
              onClick={onSkillClick}
              disabled={loading || disabled}
            />
          </Tooltip>
          <Tooltip title="资源（知识库/MCP）">
            <Button
              size="small"
              type="text"
              icon={<PlusOutlined />}
              onClick={onResourceClick}
              disabled={loading || disabled}
            />
          </Tooltip>
        </div>
        
        {/* 右侧：字符计数 */}
        <span style={{ fontSize: 11, color: charCountColor }}>
          {value.length}/{maxLength}
        </span>
      </div>

      {/* 输入框主体 + 发送按钮 */}
      <div style={{ position: 'relative' }}>
        <textarea
          ref={textareaRef}
          value={value}
          onChange={(e) => setValue(e.target.value.slice(0, maxLength))}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          disabled={disabled}
          rows={2}
          style={{
            width: '100%',
            minHeight: 44,
            padding: '6px 56px 6px 8px',
            border: 'none',
            borderRadius: 8,
            resize: 'none',
            fontSize: 14,
            lineHeight: 1.5,
            outline: 'none',
            fontFamily: 'inherit',
            boxSizing: 'border-box',
            background: 'transparent',
            color: '#333',
          }}
        />

        {/* 发送/停止按钮 - 绝对定位在右下角 */}
        <div style={{
          position: 'absolute',
          right: 6,
          bottom: 4,
        }}>
          {loading && onStop ? (
            <Button
              danger
              size="small"
              shape="circle"
              icon={<StopOutlined />}
              onClick={onStop}
            />
          ) : (
            <Button
              type="primary"
              size="small"
              shape="circle"
              icon={<SendOutlined />}
              onClick={handleSend}
              disabled={!value.trim() || disabled}
              style={{ background: !value.trim() ? undefined : '#4f46e5' }}
            />
          )}
        </div>
      </div>
    </div>
  );
};

export default EnhancedMessageInput;

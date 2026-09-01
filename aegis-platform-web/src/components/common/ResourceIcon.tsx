/**
 * @file 资源图标
 * @description 根据资源类型渲染对应图标
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import {
  ApiOutlined,
  DatabaseOutlined,
  ToolOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { ResourceType } from '@/types/enum';

interface ResourceIconProps {
  /** 资源类型 */
  type: ResourceType;
  /** 自定义类名 */
  className?: string;
}

/** 资源类型 →图标映射 */
const ICON_MAP: Record<ResourceType, React.ReactNode> = {
  [ResourceType.SKILL]: <ThunderboltOutlined />,
  [ResourceType.KNOWLEDGE_BASE]: <DatabaseOutlined />,
  [ResourceType.MCP]: <ApiOutlined />,
  [ResourceType.TOOL]: <ToolOutlined />,
  [ResourceType.DATASET]: <DatabaseOutlined />,
};

export const ResourceIcon: React.FC<ResourceIconProps> = ({ type, className }) => (
  <span className={className}>{ICON_MAP[type]}</span>
);

export default ResourceIcon;
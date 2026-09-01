/**
 * @file 版本标签
 * @description 渲染资源版本号标签
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Tag } from 'antd';

interface VersionTagProps {
  /** 版本号 */
  version: string;
}

export const VersionTag: React.FC<VersionTagProps> = ({ version }) => (
  <Tag color="geekblue">v{version}</Tag>
);

export default VersionTag;
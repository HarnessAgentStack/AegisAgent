/**
 * @file 404 页面
 * @description 未匹配路由兜底页
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';
import { ROUTE_PATH } from '@/utils/constants';

const NotFound: React.FC = () => {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title="404"
      subTitle="抱歉，您访问的页面不存在"
      extra={
        <Button type="primary" onClick={() => navigate(ROUTE_PATH.WORKBENCH)}>
          返回工作台
        </Button>
      }
    />
  );
};

export default NotFound;
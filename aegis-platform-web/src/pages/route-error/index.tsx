/**
 * @file 路由错误兜底页
 * @description React Router errorElement：渲染路由级异常（渲染错误/数据加载错误），提供回工作台入口
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Button, Result } from 'antd';
import { useNavigate, useRouteError } from 'react-router-dom';
import { ROUTE_PATH } from '@/utils/constants';

const RouteErrorPage: React.FC = () => {
  const navigate = useNavigate();
  const error = useRouteError();
  const detail = error instanceof Error ? error.message : undefined;

  return (
    <Result
      status="error"
      title="页面出现异常"
      subTitle={detail ? `错误信息：${detail}` : '请稍后重试，或返回工作台继续操作'}
      extra={
        <Button type="primary" onClick={() => navigate(ROUTE_PATH.WORKBENCH, { replace: true })}>
          返回工作台
        </Button>
      }
    />
  );
};

export default RouteErrorPage;

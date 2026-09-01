/**
 * @file 根组件
 * @description 装配平台路由（RouterProvider）、HTTP 错误处理回调注入、全局 UI 上下文
 * @author wang.zhen
 * @since 1.0.0
 */
import { useEffect } from 'react';
import { App as AntdApp } from 'antd';
import { RouterProvider } from 'react-router-dom';
import { router } from '@/router';
import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { setErrorHandler } from '@/api/request';

/**
 * 应用根组件
 * @description 通过 RouterProvider 渲染平台路由树，外层包裹 ErrorBoundary 防止未捕获异常导致白屏。
 *              挂载时向 request 模块注入 antd.message.error 回调，实现 UI 解耦。
 */
const App: React.FC = () => {
  const { message } = AntdApp.useApp();

  useEffect(() => {
    // 注入业务错误处理回调：请求层不再直接依赖 antd，由 App 根组件绑定 UI 反馈
    setErrorHandler((msg: string, _code?: number) => {
      message.error(msg);
    });
  }, [message]);

  return (
    <ErrorBoundary>
      <RouterProvider router={router} />
    </ErrorBoundary>
  );
};

export default App;

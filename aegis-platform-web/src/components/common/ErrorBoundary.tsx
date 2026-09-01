/**
 * @file React 错误边界组件
 * @description 捕获子组件树中的 JavaScript 错误，防止整个应用崩溃
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';

interface ErrorBoundaryProps {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
  errorInfo: React.ErrorInfo | null;
}

/**
 * 返回首页按钮（需在 Router 内部使用）
 */
const BackHomeButton: React.FC = () => {
  const navigate = useNavigate();
  return (
    <Button type="primary" onClick={() => navigate('/')}>
      返回首页
    </Button>
  );
};

/**
 * 错误边界组件
 * @description 捕获子组件渲染过程中的错误，展示友好的错误提示页面
 */
class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<ErrorBoundaryState> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo): void {
    this.setState({ errorInfo });
    console.error('[ErrorBoundary] 捕获到异常:', error, errorInfo);
  }

  handleReload = (): void => {
    this.setState({ hasError: false, error: null, errorInfo: null });
  };

  render(): React.ReactNode {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <div style={{ maxWidth: 600, margin: '80px auto' }}>
          <Result
            status="error"
            title="页面出现异常"
            subTitle={this.state.error?.message || '发生了未知错误，请尝试重新加载页面'}
            extra={[
              <Button key="reload" onClick={this.handleReload}>
                重新加载
              </Button>,
              <BackHomeButton key="home" />,
            ]}
          />
        </div>
      );
    }

    return this.props.children;
  }
}

export { ErrorBoundary };

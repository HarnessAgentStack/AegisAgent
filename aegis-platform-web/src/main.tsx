/**
 * @file 应用入口
 * @description 挂载 React 根节点，装配 QueryClient / AntD ConfigProvider / 路由
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntdApp, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App';
import './styles/global.css';
import { migrateLegacyKeys } from './utils/storage';
import { useThemeStore } from './stores/themeStore';

dayjs.locale('zh-cn');

/**
 * 启动前迁移历史遗留存储 Key 到单轨命名空间。
 * - 旧格式：aegis_XXX 常量 + storage 再次加前缀 = aegis_aegis_XXX
 * - 新格式：常量无前缀 + storage 加前缀 = aegis_XXX
 * 幂等：已迁移或无遗留数据时静默跳过。
 */
migrateLegacyKeys();

/** React Query 客户端实例：统一默认策略 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30_000,
      gcTime: 5 * 60_000,
    },
  },
});

/**
 * 应用根 Provider：根据主题状态切换 antd 算法（亮/暗）+ 基础 token。
 * data-theme 属性由 themeStore 在初始化/切换时写入 <html>，CSS 变量自动生效。
 */
const AppProviders: React.FC = () => {
  const mode = useThemeStore((s) => s.mode);
  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider
        locale={zhCN}
        theme={{
          algorithm: mode === 'dark' ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
          token: {
            colorPrimary: '#1677ff',
            borderRadius: 6,
            fontSize: 14,
          },
        }}
      >
        <AntdApp>
          <App />
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
};

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppProviders />
  </React.StrictMode>,
);
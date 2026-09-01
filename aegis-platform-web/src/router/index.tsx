/**
 * @file 路由实例
 * @description 创建 Browser Router 实例并统一导出
 * @author wang.zhen
 * @since 1.0.0
 */
import { createBrowserRouter } from 'react-router-dom';
import { routes } from './routes';

/** 平台路由实例 */
export const router = createBrowserRouter(routes);

export default router;
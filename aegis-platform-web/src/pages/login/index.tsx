/**
 * @file 登录页
 * @description 用户登录入口，支持账号密码登录
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useState } from 'react';
import { App, Button, Card, Form, Input, Typography } from 'antd';
import { LockOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';
import { ROUTE_PATH } from '@/utils/constants';
import type { LoginParams } from '@/types/user';

const { Title } = Typography;

const Login: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: LoginParams) => {
    setLoading(true);
    try {
      await login(values);
      const from = (location.state as { from?: { pathname: string } })?.from?.pathname;
      navigate(from ?? ROUTE_PATH.WORKBENCH, { replace: true });
    } catch (err: unknown) {
      // 边界断言：axios 错误可能有 response.data.message，普通 Error 有 message
      const errObj = err as { response?: { data?: { message?: string } }; message?: string };
      const msg = errObj.response?.data?.message || errObj.message || '登录失败，请检查账号或密码';
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        height: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 380 }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Title level={3} style={{ marginBottom: 4 }}>
            Aegis Platform
          </Title>
          <Typography.Text type="secondary">企业级通用智能体平台</Typography.Text>
        </div>
        <Form<LoginParams> layout="vertical" onFinish={onFinish}>
          <Form.Item
            name="tenantCode"
            label="租户标识"
            rules={[{ required: true, message: '请输入租户标识' }]}
          >
            <Input prefix={<TeamOutlined />} placeholder="租户编码" />
          </Form.Item>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
};

export default Login;
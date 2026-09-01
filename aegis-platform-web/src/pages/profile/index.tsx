/**
 * @file 个人设置
 * @description 当前用户基础资料修改 + 修改密码
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect } from 'react';
import { App, Button, Card, Col, Form, Input, Row } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import { useAuthStore } from '@/stores/authStore';
import { authApi, type UpdateProfileParams, type ChangePasswordParams } from '@/api/auth';

interface ProfileFormValues {
  nickname?: string;
  avatar?: string;
  email?: string;
  phone?: string;
}

interface PasswordFormValues {
  oldPassword: string;
  newPassword: string;
  confirm: string;
}

const ProfilePage: React.FC = () => {
  const { user } = useAuth();
  const setUser = useAuthStore((s) => s.setUser);
  const { message } = App.useApp();
  const [profileForm] = Form.useForm<ProfileFormValues>();
  const [pwdForm] = Form.useForm<PasswordFormValues>();

  useEffect(() => {
    // 拉取最新用户信息填充资料表单
    authApi.me().then((info) => {
      profileForm.setFieldsValue({
        nickname: info.nickname,
        avatar: info.avatar,
        email: info.email,
        phone: info.phone,
      });
    }).catch(() => {
      profileForm.setFieldsValue({
        nickname: user?.nickname,
        avatar: user?.avatar,
        email: user?.email,
        phone: user?.phone,
      });
    });
  }, [profileForm, user]);

  const handleProfileSave = async (values: ProfileFormValues) => {
    try {
      await authApi.updateProfile(values as UpdateProfileParams);
      // 更新本地用户态，Header 昵称/头像即时刷新
      if (user) {
        setUser({
          ...user,
          nickname: values.nickname ?? user.nickname,
          avatar: values.avatar ?? user.avatar,
          email: values.email ?? user.email,
          phone: values.phone ?? user.phone,
        });
      }
      message.success('个人资料保存成功');
    } catch (e) {
      const err = e as { response?: { data?: { message?: string } }; message?: string };
      message.error(err.response?.data?.message || err.message || '保存失败');
    }
  };

  const handlePasswordChange = async (values: PasswordFormValues) => {
    try {
      await authApi.changePassword({ oldPassword: values.oldPassword, newPassword: values.newPassword } as ChangePasswordParams);
      message.success('密码修改成功，请使用新密码重新登录');
      pwdForm.resetFields();
    } catch (e) {
      const err = e as { response?: { data?: { message?: string } }; message?: string };
      message.error(err.response?.data?.message || err.message || '密码修改失败');
    }
  };

  return (
    <div>
      <PageHeader title="个人设置" desc="管理当前用户的基础资料与登录密码" />
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="基础信息">
            <Form<ProfileFormValues>
              form={profileForm}
              layout="vertical"
              onFinish={handleProfileSave}
            >
              <Form.Item label="用户名">
                <Input value={user?.username} disabled prefix={<UserOutlined />} />
              </Form.Item>
              <Form.Item name="nickname" label="昵称">
                <Input placeholder="请输入昵称" />
              </Form.Item>
              <Form.Item name="avatar" label="头像URL">
                <Input placeholder="请输入头像URL" />
              </Form.Item>
              <Form.Item name="email" label="邮箱">
                <Input placeholder="请输入邮箱" />
              </Form.Item>
              <Form.Item name="phone" label="手机号">
                <Input placeholder="请输入手机号" />
              </Form.Item>
              <Button type="primary" htmlType="submit">保存资料</Button>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="修改密码">
            <Form<PasswordFormValues>
              form={pwdForm}
              layout="vertical"
              onFinish={handlePasswordChange}
            >
              <Form.Item
                name="oldPassword"
                label="旧密码"
                rules={[{ required: true, message: '请输入旧密码' }]}
              >
                <Input.Password prefix={<LockOutlined />} placeholder="旧密码" />
              </Form.Item>
              <Form.Item
                name="newPassword"
                label="新密码"
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 8, message: '密码至少 8 位' },
                ]}
              >
                <Input.Password prefix={<LockOutlined />} placeholder="新密码" />
              </Form.Item>
              <Form.Item
                name="confirm"
                label="确认新密码"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '请确认新密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('newPassword') === value) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password prefix={<LockOutlined />} placeholder="确认新密码" />
              </Form.Item>
              <Button type="primary" htmlType="submit">修改密码</Button>
            </Form>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ProfilePage;

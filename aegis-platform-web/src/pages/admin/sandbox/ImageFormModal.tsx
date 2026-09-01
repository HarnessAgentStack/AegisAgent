/**
 * @file 基础镜像 - 创建/编辑弹窗
 * @description 注册 Docker Image，支持 Docker Hub / Harbor 两种仓库类型
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Form, Input, InputNumber, Modal, Select } from 'antd';
import type { SandboxBaseImage } from '@/api/sandbox';
import { imageApi } from '@/api/sandbox';
import {
  ENABLED_STATUS_OPTIONS,
  REGISTRY_TYPE_OPTIONS,
  type ImageFormValues,
} from './constants';

interface ImageFormModalProps {
  visible: boolean;
  editRecord: SandboxBaseImage | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const ImageFormModal: React.FC<ImageFormModalProps> = ({
  visible,
  editRecord,
  onCancel,
  onSuccess,
}) => {
  const { message } = App.useApp();
  const [formLoading, setFormLoading] = useState(false);
  const [form] = Form.useForm<ImageFormValues>();
  const editId = editRecord?.id ?? null;

  useEffect(() => {
    if (!visible) return;
    form.resetFields();
    if (editRecord) {
      form.setFieldsValue({
        imageCode: editRecord.imageCode,
        imageName: editRecord.imageName,
        description: editRecord.description,
        registryType: editRecord.registryType,
        registry: editRecord.registry,
        repository: editRecord.repository,
        tag: editRecord.tag,
        digest: editRecord.digest,
        imageSizeMb: editRecord.imageSizeMb,
        status: editRecord.status ?? 'ENABLED',
      });
    } else {
      form.setFieldsValue({
        registryType: 'DOCKER_HUB',
        registry: 'docker.io',
        tag: 'latest',
        status: 'ENABLED',
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  const submitForm = async () => {
    try {
      const values = await form.validateFields();
      setFormLoading(true);
      const payload: SandboxBaseImage = { ...values };
      if (editId !== null) {
        await imageApi.update({ ...payload, id: editId });
        message.success('镜像更新成功');
      } else {
        await imageApi.create(payload);
        message.success('镜像创建成功');
      }
      onSuccess();
    } catch (err) {
      console.error(err);
    } finally {
      setFormLoading(false);
    }
  };

  return (
    <Modal
      title={editId !== null ? '编辑基础镜像' : '注册基础镜像'}
      open={visible}
      onCancel={onCancel}
      onOk={submitForm}
      confirmLoading={formLoading}
      width={720}
      okText={editId !== null ? '保存' : '创建'}
      destroyOnClose
    >
      <Form<ImageFormValues> form={form} layout="vertical">
        <Form.Item
          name="imageCode"
          label="镜像编码"
          rules={[
            { required: true, message: '请输入镜像编码' },
            { pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: '大写字母开头，3-64 字符' },
          ]}
          tooltip="租户内唯一，创建后不可修改"
        >
          <Input placeholder="如 IMG_PYTHON_DS" disabled={editId !== null} />
        </Form.Item>
        <Form.Item
          name="imageName"
          label="镜像名称"
          rules={[{ required: true, message: '请输入镜像名称' }]}
        >
          <Input placeholder="如 python-datascience" />
        </Form.Item>
        <Form.Item name="description" label="描述">
          <Input.TextArea rows={2} placeholder="包含哪些包/环境（如 python3.11 + pandas + numpy）" />
        </Form.Item>
        <Form.Item
          name="registryType"
          label="镜像仓库类型"
          rules={[{ required: true }]}
          tooltip="决定使用哪个 SPI 实现拉取镜像"
        >
          <Select options={REGISTRY_TYPE_OPTIONS} />
        </Form.Item>
        <Form.Item
          name="registry"
          label="镜像仓库地址"
          rules={[{ required: true, message: '请输入镜像仓库地址' }]}
        >
          <Input placeholder="如 docker.io / harbor.aegis.internal" />
        </Form.Item>
        <Form.Item
          name="repository"
          label="镜像仓库路径"
          rules={[{ required: true, message: '请输入镜像仓库路径' }]}
          tooltip="不含 registry 与 tag，如 library/python-datascience"
        >
          <Input placeholder="如 library/python-datascience" />
        </Form.Item>
        <Form.Item
          name="tag"
          label="镜像标签"
          rules={[{ required: true, message: '请输入镜像标签' }]}
        >
          <Input placeholder="如 3.11-slim" />
        </Form.Item>
        <Form.Item name="digest" label="镜像 SHA256 摘要" tooltip="可选，用于校验镜像完整性">
          <Input placeholder="如 sha256:abc123..." />
        </Form.Item>
        <Form.Item name="imageSizeMb" label="镜像大小（MB）">
          <InputNumber min={0} style={{ width: '100%' }} placeholder="如 580" />
        </Form.Item>
        <Form.Item name="status" label="状态" rules={[{ required: true }]}>
          <Select options={ENABLED_STATUS_OPTIONS} />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default ImageFormModal;

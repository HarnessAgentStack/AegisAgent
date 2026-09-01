/**
 * @file 沙箱池 - 创建/编辑弹窗
 * @description 池 CRUD 表单，关联基础镜像，配置资源配额/网络策略/生命周期参数
 *              两参数驱动模型：移除 totalCount/minIdle/maxScale/timeoutRecycleMin/
 *              healthCheckIntervalSec/imageVersion，仅保留 minInstances/maxInstances/
 *              idleTimeoutMin，预热与回收由 Reconcile 自动执行
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Card, Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import type { SandboxBaseImage, SandboxPool } from '@/api/sandbox';
import { imageApi, poolApi } from '@/api/sandbox';
import {
  NETWORK_POLICY_OPTIONS,
  POOL_STATUS_OPTIONS,
  POOL_TYPE_OPTIONS,
  type PoolFormValues,
} from './constants';

interface PoolFormModalProps {
  visible: boolean;
  editRecord: SandboxPool | null;
  onCancel: () => void;
  onSuccess: () => void;
}

const PoolFormModal: React.FC<PoolFormModalProps> = ({
  visible,
  editRecord,
  onCancel,
  onSuccess,
}) => {
  const { message } = App.useApp();
  const [formLoading, setFormLoading] = useState(false);
  const [imageList, setImageList] = useState<SandboxBaseImage[]>([]);
  const [imageLoading, setImageLoading] = useState(false);
  const [form] = Form.useForm<PoolFormValues>();
  const editId = editRecord?.id ?? null;

  /** 加载启用镜像列表（下拉选项） */
  const loadImages = async () => {
    setImageLoading(true);
    try {
      const res = await imageApi.list();
      const arr = Array.isArray(res) ? res : [];
      setImageList(arr);
    } catch {
      /* 弹错已处理 */
    } finally {
      setImageLoading(false);
    }
  };

  useEffect(() => {
    if (!visible) return;
    loadImages();
    form.resetFields();
    if (editRecord) {
      form.setFieldsValue({
        poolCode: editRecord.poolCode,
        poolName: editRecord.poolName,
        poolType: editRecord.poolType,
        baseImageId: editRecord.baseImageId,
        applicableScene: editRecord.applicableScene,
        minInstances: editRecord.minInstances,
        maxInstances: editRecord.maxInstances,
        idleTimeoutMin: editRecord.idleTimeoutMin,
        networkPolicy: editRecord.networkPolicy,
        cpuLimit: editRecord.cpuLimit,
        memLimitMb: editRecord.memLimitMb,
        diskLimitGb: editRecord.diskLimitGb,
        status: editRecord.status ?? 'ENABLED',
      });
    } else {
      form.setFieldsValue({
        poolType: 'STANDARD',
        minInstances: 1,
        maxInstances: 3,
        idleTimeoutMin: 30,
        networkPolicy: 'RESTRICTED',
        cpuLimit: '1',
        memLimitMb: 512,
        diskLimitGb: 5,
        status: 'ENABLED',
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  const submitForm = async () => {
    try {
      const values = await form.validateFields();
      setFormLoading(true);
      const payload: SandboxPool = { ...values };
      if (editId !== null) {
        await poolApi.update({ ...payload, id: editId });
        message.success('沙箱池更新成功');
      } else {
        await poolApi.create(payload);
        message.success('沙箱池创建成功，已执行 K8s 资源预检查并创建');
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
      title={editId !== null ? '编辑沙箱池' : '新建沙箱池'}
      open={visible}
      onCancel={onCancel}
      onOk={submitForm}
      confirmLoading={formLoading}
      width={880}
      okText={editId !== null ? '保存' : '创建'}
      destroyOnClose
    >
      <Form<PoolFormValues> form={form} layout="vertical">
        <Card size="small" title="基本信息" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="poolCode"
                label="池编码"
                rules={[
                  { required: true, message: '请输入池编码' },
                  { pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: '大写字母开头，3-64 字符' },
                ]}
                tooltip="租户内唯一，创建后不可修改"
              >
                <Input placeholder="如 POOL_STD_T0" disabled={editId !== null} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="poolName" label="池名称" rules={[{ required: true, message: '请输入池名称' }]}>
                <Input placeholder="如 标准执行池" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="poolType" label="池类型" rules={[{ required: true }]}>
                <Select options={POOL_TYPE_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="baseImageId"
                label="基础镜像"
                rules={[{ required: true, message: '请选择基础镜像' }]}
                tooltip="池内实例统一使用该镜像创建 Pod"
              >
                <Select
                  loading={imageLoading}
                  placeholder="选择已启用的镜像"
                  options={imageList.map((img) => ({
                    value: img.id,
                    label: `${img.imageName} (${img.registry}/${img.repository}:${img.tag})`,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="applicableScene" label="适用场景">
                <Input.TextArea rows={2} placeholder="说明该池适用的智能体场景" />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card size="small" title="生命周期参数" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="minInstances"
                label="最小实例数"
                rules={[
                  { required: true, message: '请输入最小实例数' },
                  { type: 'number', min: 0, message: '最小实例数必须 >= 0' },
                  {
                    validator: (_, value) => {
                      const max = form.getFieldValue('maxInstances');
                      if (value !== undefined && max !== undefined && value > max) {
                        return Promise.reject(new Error('最小实例数不能大于最大实例数'));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
                tooltip="始终保持的干净 IDLE 实例数（预热基准）"
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="maxInstances"
                label="最大实例数"
                rules={[
                  { required: true, message: '请输入最大实例数' },
                  { type: 'number', min: 1, message: '最大实例数必须 >= 1' },
                  {
                    validator: (_, value) => {
                      const min = form.getFieldValue('minInstances');
                      if (value !== undefined && min !== undefined && min > value) {
                        return Promise.reject(new Error('最大实例数不能小于最小实例数'));
                      }
                      return Promise.resolve();
                    },
                  },
                ]}
                tooltip="总实例数上限（缩容阈值）"
              >
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="idleTimeoutMin"
                label="空闲超时（分钟）"
                rules={[
                  { required: true, message: '请输入空闲超时' },
                  { type: 'number', min: 1, message: '空闲超时必须 >= 1' },
                ]}
                tooltip="脏 IDLE 超时触发工作区重初始化"
              >
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Card>

        <Card size="small" title="资源与网络" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={6}>
              <Form.Item name="cpuLimit" label="CPU 限制（核）" rules={[{ required: true }]} tooltip="单实例最大 CPU，如 0.5/1/2">
                <Input placeholder="如 1" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="memLimitMb" label="内存限制（MB）" rules={[{ required: true }]}>
                <InputNumber min={128} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="diskLimitGb" label="磁盘限制（GB）">
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="networkPolicy" label="网络策略" rules={[{ required: true }]}>
                <Select options={NETWORK_POLICY_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="status" label="状态" rules={[{ required: true }]}>
                <Select options={POOL_STATUS_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
        </Card>
      </Form>
    </Modal>
  );
};

export default PoolFormModal;

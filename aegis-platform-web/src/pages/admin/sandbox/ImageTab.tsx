/**
 * @file 基础镜像 Tab - 表格管理
 * @description 镜像注册列表，支持编码/名称搜索、状态筛选、创建/编辑/启停/删除
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Input, Popconfirm, Select, Table, Tag } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SandboxBaseImage } from '@/api/sandbox';
import { imageApi } from '@/api/sandbox';
import {
  ENABLED_STATUS_OPTIONS,
  ENABLED_STATUS_MAP,
  REGISTRY_TYPE_MAP,
  normalizePage,
} from './constants';

interface ImageTabProps {
  onCreate: () => void;
  onEdit: (record: SandboxBaseImage) => void;
  onTotalChange?: (total: number) => void;
  refreshSignal?: number;
}

const ImageTab: React.FC<ImageTabProps> = ({
  onCreate,
  onEdit,
  onTotalChange,
  refreshSignal,
}) => {
  const { message } = App.useApp();
  const [keyword, setKeyword] = useState('');
  const [input, setInput] = useState('');
  const [status, setStatus] = useState<string>('all');
  const [list, setList] = useState<SandboxBaseImage[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await imageApi.page({
        page,
        size,
        imageCode: keyword || undefined,
        imageName: keyword || undefined,
        status: status !== 'all' ? status : undefined,
      });
      const { list: records, total: t } = normalizePage<SandboxBaseImage>(res);
      setList(records);
      setTotal(t);
      onTotalChange?.(t);
    } catch {
      /* 弹错已处理 */
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, status, page, size, refreshSignal]);

  const toggleStatus = async (record: SandboxBaseImage) => {
    try {
      const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
      await imageApi.updateStatus(record.id!, next);
      message.success(next === 'ENABLED' ? '镜像已启用' : '镜像已停用');
      loadData();
    } catch {
      /* 弹错已处理 */
    }
  };

  const remove = async (record: SandboxBaseImage) => {
    try {
      await imageApi.delete(record.id!);
      message.success('镜像已删除');
      loadData();
    } catch {
      /* 弹错已处理 */
    }
  };

  const columns: ColumnsType<SandboxBaseImage> = [
    { title: '镜像编码', dataIndex: 'imageCode', width: 180 },
    { title: '镜像名称', dataIndex: 'imageName', width: 180 },
    {
      title: '仓库类型',
      dataIndex: 'registryType',
      width: 120,
      render: (t: string) => {
        const cfg = REGISTRY_TYPE_MAP[t];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : t ?? '-';
      },
    },
    {
      title: '完整镜像引用',
      width: 320,
      render: (_: unknown, r: SandboxBaseImage) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {r.registry}/{r.repository}:{r.tag}
        </span>
      ),
    },
    { title: '大小', dataIndex: 'imageSizeMb', width: 100, render: (v?: number) => (v ? `${v} MB` : '-') },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s?: string) => {
        const cfg = ENABLED_STATUS_MAP[s ?? 'ENABLED'];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : s ?? '-';
      },
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (v?: string) => v ?? '-',
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      render: (_: unknown, record: SandboxBaseImage) => (
        <div style={{ display: 'flex', gap: 10, fontSize: 13, whiteSpace: 'nowrap' }}>
          <a onClick={() => onEdit(record)}>
            <EditOutlined /> 编辑
          </a>
          <a onClick={() => toggleStatus(record)} style={{ color: record.status === 'ENABLED' ? '#ef4444' : '#10b981' }}>
            {record.status === 'ENABLED' ? '停用' : '启用'}
          </a>
          <Popconfirm
            title={`确认删除镜像「${record.imageName}」？`}
            description="删除后不可恢复，引用该镜像的池将无法预热"
            onConfirm={() => remove(record)}
          >
            <a style={{ color: '#ff4d4f' }}>
              <DeleteOutlined /> 删除
            </a>
          </Popconfirm>
        </div>
      ),
    },
  ];

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <Input.Search
            placeholder="搜索镜像编码/名称"
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              if (e.target.value === '') {
                setKeyword('');
                setPage(1);
              }
            }}
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
            }}
            allowClear
            style={{ width: 240 }}
            enterButton
          />
          <Select
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
            options={[{ value: 'all', label: '全部状态' }, ...ENABLED_STATUS_OPTIONS]}
            style={{ width: 140 }}
          />
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={onCreate} style={{ flexShrink: 0 }}>
          注册镜像
        </Button>
      </div>

      <Table<SandboxBaseImage>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        scroll={{ x: 1300 }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, sz) => {
            setPage(p);
            setSize(sz);
          },
        }}
      />
    </>
  );
};

export default ImageTab;

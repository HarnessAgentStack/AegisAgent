/**
 * @file 工具管理
 * @description 工具列表 + 只读详情查看（显示 inputSchema / outputSchema）
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { PageHeader } from '@/components/common/PageHeader';
import { SecurityLevelTag } from '@/components/common/SecurityLevelTag';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { SecurityLevel } from '@/types/enum';
import type { Tool } from '@/types/resource';
import { toolApi } from '@/api/resource';
import { safeJsonParse } from '@/utils/number';

const { Text, Paragraph } = Typography;

/** 工具类型 */
type ToolType = Tool['toolType'];

/** 工具来源类型 */
type SourceType = Tool['sourceType'];

/** 工具状态 */
type ToolStatus = Tool['status'];

/** 工具类型 ->Tag 颜色 / 文案（兼容后端 ToolType 枚举与历史值） */
const TOOL_TYPE_TAG: Partial<Record<ToolType, { color: string; text: string }>> = {
  READONLY: { color: 'green', text: '只读操作' },
  INTERNAL_API: { color: 'blue', text: '内部 API' },
  WRITE: { color: 'orange', text: '写操作' },
  EXTERNAL_NETWORK: { color: 'geekblue', text: '外部网络' },
  CODE_EXEC: { color: 'red', text: '代码执行' },
  HIGH_RISK: { color: 'magenta', text: '高风险' },
  BUILTIN: { color: 'blue', text: '内置' },
  CUSTOM: { color: 'purple', text: '自定义' },
  MCP_BOUND: { color: 'cyan', text: 'MCP 绑定' },
  SKILL_BOUND: { color: 'geekblue', text: '技能绑定' },
};

/** 工具来源类型 →Tag 颜色 / 文案（兼容后端 BUILTIN/MCP 与历史值） */
const SOURCE_TYPE_TAG: Partial<Record<SourceType, { color: string; text: string }>> = {
  BUILTIN: { color: 'blue', text: '平台内置' },
  MCP: { color: 'cyan', text: 'MCP' },
  SYSTEM: { color: 'default', text: '系统' },
  USER: { color: 'blue', text: '用户' },
  SKILL: { color: 'geekblue', text: '技能' },
};

/** 工具状态 →Tag 颜色 / 文案（兼容后端 NORMAL/DISABLED 与历史值） */
const TOOL_STATUS_TAG: Partial<Record<ToolStatus, { color: string; text: string }>> = {
  NORMAL: { color: 'success', text: '正常' },
  DISABLED: { color: 'default', text: '已禁用' },
  ACTIVE: { color: 'success', text: '可用' },
  INACTIVE: { color: 'default', text: '未启用' },
  DEPRECATED: { color: 'warning', text: '已废弃' },
};

const ToolPage: React.FC = () => {
  const { message } = App.useApp();
  const [keyword, setKeyword] = useState('');
  const [toolType, setToolType] = useState<string>('all');
  const [sourceType, setSourceType] = useState<string>('all');
  const [loading, setLoading] = useState(false);
  const [tools, setTools] = useState<Tool[]>([]);

  // 详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<Tool | null>(null);

  /** 过滤工具列表 */
  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    return tools.filter((t) => {
      const matchKw = kw ? t.toolName.toLowerCase().includes(kw) || t.toolCode.toLowerCase().includes(kw) : true;
      const matchType = toolType === 'all' ? true : t.toolType === toolType;
      const matchSource = sourceType === 'all' ? true : t.sourceType === sourceType;
      return matchKw && matchType && matchSource;
    });
  }, [keyword, toolType, sourceType, tools]);

  /** 加载列表（silent=true 时为挂载静默加载，不弹提示） */
  const load = async (silent = false) => {
    setLoading(true);
    try {
      const list = await toolApi.list();
      setTools(list);
      if (!silent) message.success('已刷新工具列表');
    } catch {
      /* 弹错已处理 */
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(true); }, []);

  /** 打开详情弹窗 */
  const openDetail = (record: Tool) => {
    setDetailRecord(record);
    setDetailVisible(true);
  };

  /** 格式化 JSON 展示 */
  const prettyJson = (raw?: string): string => {
    if (!raw) return '—';
    const parsed = safeJsonParse(raw);
    return parsed != null ? JSON.stringify(parsed, null, 2) : raw;
  };

  // ===== 工具表格列 =====
  const columns: ColumnsType<Tool> = [
    { title: '工具编码', dataIndex: 'toolCode', width: 180 },
    { title: '名称', dataIndex: 'toolName' },
    {
      title: '工具类型',
      dataIndex: 'toolType',
      width: 110,
      render: (t: ToolType) => {
        const cfg = TOOL_TYPE_TAG[t];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{t}</Tag>;
      },
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      width: 90,
      render: (s: SourceType) => {
        const cfg = SOURCE_TYPE_TAG[s];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{s}</Tag>;
      },
    },
    {
      title: '安全级别',
      dataIndex: 'securityLevel',
      width: 120,
      render: (level: SecurityLevel) => <SecurityLevelTag level={level} />,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: ToolStatus) => {
        const cfg = TOOL_STATUS_TAG[s];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{s}</Tag>;
      },
    },
    {
      title: '读写',
      dataIndex: 'readOnly',
      width: 80,
      render: (v?: boolean) =>
        v ? <Tag color="green">只读</Tag> : <Tag color="orange">写</Tag>,
    },
    {
      title: '操作',
      width: 110,
      render: (_: unknown, record: Tool) => (
        <a onClick={() => openDetail(record)}>
          <EyeOutlined /> 查看
        </a>
      ),
    },
  ];

  /** 渲染详情弹窗 */
  const renderDetailModal = () => {
    if (!detailRecord) return null;
    const r = detailRecord;
    return (
      <Modal
        title={`工具详情 - ${r.toolName}`}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={820}
      >
        <Descriptions column={2} bordered size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="工具编码">{r.toolCode}</Descriptions.Item>
          <Descriptions.Item label="名称">{r.toolName}</Descriptions.Item>
          <Descriptions.Item label="工具类型">
            {(() => { const cfg = TOOL_TYPE_TAG[r.toolType]; return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{r.toolType}</Tag>; })()}
          </Descriptions.Item>
          <Descriptions.Item label="来源">
            {(() => { const cfg = SOURCE_TYPE_TAG[r.sourceType]; return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{r.sourceType}</Tag>; })()}
          </Descriptions.Item>
          <Descriptions.Item label="安全级别">
            <SecurityLevelTag level={r.securityLevel} />
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            {(() => { const cfg = TOOL_STATUS_TAG[r.status]; return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : <Tag>{r.status}</Tag>; })()}
          </Descriptions.Item>
          {r.lifeStatus && (
            <Descriptions.Item label="生命周期">
              <LifeStatusTag status={r.lifeStatus} />
            </Descriptions.Item>
          )}
          <Descriptions.Item label="是否只读">
            {r.readOnly ? <Tag color="green">只读</Tag> : <Tag color="orange">含写操作</Tag>}
          </Descriptions.Item>
          {r.sourceRef && (
            <Descriptions.Item label="来源引用" span={2}>
              <Text code>{r.sourceRef}</Text>
            </Descriptions.Item>
          )}
          <Descriptions.Item label="描述" span={2}>
            {r.description ?? '—'}
          </Descriptions.Item>
        </Descriptions>

        <Card size="small" title="入参 Schema (Input Schema)" style={{ marginBottom: 12 }}>
          <Paragraph>
            <pre
              style={{
                margin: 0,
                padding: 12,
                background: '#f5f5f5',
                borderRadius: 6,
                fontSize: 12,
                fontFamily: 'monospace',
                overflowX: 'auto',
                maxHeight: 240,
              }}
            >
              {prettyJson(r.inputSchema)}
            </pre>
          </Paragraph>
        </Card>

        <Card size="small" title="出参 Schema (Output Schema)">
          <Paragraph>
            <pre
              style={{
                margin: 0,
                padding: 12,
                background: '#f5f5f5',
                borderRadius: 6,
                fontSize: 12,
                fontFamily: 'monospace',
                overflowX: 'auto',
                maxHeight: 240,
              }}
            >
              {prettyJson(r.outputSchema)}
            </pre>
          </Paragraph>
        </Card>
      </Modal>
    );
  };

  return (
    <div>
      <PageHeader title="工具管理" desc="工具列表 · 平台工具与外部 MCP/技能绑定工具一览" />
      <Card>
        <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
          <Space>
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索工具编码/名称"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              allowClear
              style={{ width: 240 }}
            />
            <Select
              value={toolType}
              onChange={setToolType}
              style={{ width: 140 }}
              options={[
                { value: 'all', label: '全部类型' },
                { value: 'READONLY', label: '只读操作' },
                { value: 'INTERNAL_API', label: '内部 API' },
                { value: 'WRITE', label: '写操作' },
                { value: 'EXTERNAL_NETWORK', label: '外部网络' },
                { value: 'CODE_EXEC', label: '代码执行' },
                { value: 'HIGH_RISK', label: '高风险' },
              ]}
            />
            <Select
              value={sourceType}
              onChange={setSourceType}
              style={{ width: 140 }}
              options={[
                { value: 'all', label: '全部来源' },
                { value: 'BUILTIN', label: '平台内置' },
                { value: 'MCP', label: 'MCP' },
              ]}
            />
          </Space>
          <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>
            刷新
          </Button>
        </Space>
        <Table<Tool>
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        />
      </Card>

      {renderDetailModal()}
    </div>
  );
};

export default ToolPage;

/**
 * @file 工具管理
 * @description 工具列表 + 只读详情查看（显示 inputSchema / outputSchema）
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useMemo, useState } from 'react';
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
import { LifeStatus, SecurityLevel } from '@/types/enum';
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

/** 工具类型 ->Tag 颜色 / 文案 */
const TOOL_TYPE_TAG: Record<ToolType, { color: string; text: string }> = {
  BUILTIN: { color: 'blue', text: '内置' },
  CUSTOM: { color: 'purple', text: '自定义' },
  MCP_BOUND: { color: 'cyan', text: 'MCP 绑定' },
  SKILL_BOUND: { color: 'geekblue', text: '技能绑定' },
};

/** 工具来源类型 →Tag 颜色 / 文案 */
const SOURCE_TYPE_TAG: Record<SourceType, { color: string; text: string }> = {
  SYSTEM: { color: 'default', text: '系统' },
  USER: { color: 'blue', text: '用户' },
  MCP: { color: 'cyan', text: 'MCP' },
  SKILL: { color: 'geekblue', text: '技能' },
};

/** 工具状态 →Tag 颜色 / 文案 */
const TOOL_STATUS_TAG: Record<ToolStatus, { color: string; text: string }> = {
  ACTIVE: { color: 'success', text: '可用' },
  INACTIVE: { color: 'default', text: '未启用' },
  DEPRECATED: { color: 'warning', text: '已废弃' },
};

/** 工具Mock数据 */
const TOOL_MOCK: Tool[] = [
  {
    id: '1',
    toolCode: 'TOOL_SQL_EXEC',
    toolName: 'SQL执行',
    toolType: 'BUILTIN',
    sourceType: 'SYSTEM',
    securityLevel: SecurityLevel.L3,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'ACTIVE',
    signature: 'executeSql(sql: string, datasource: string): ResultSet',
    description: '执行只读 SQL 查询并返回结果集',
    requireApproval: true,
    inputSchema: '{"type":"object","properties":{"sql":{"type":"string"},"datasource":{"type":"string"}},"required":["sql"]}',
    outputSchema: '{"type":"object","properties":{"columns":{"type":"array"},"rows":{"type":"array"}}}',
    createdAt: '2024-06-01 10:00:00',
  },
  {
    id: '2',
    toolCode: 'TOOL_HTTP_REQUEST',
    toolName: 'HTTP请求',
    toolType: 'BUILTIN',
    sourceType: 'SYSTEM',
    securityLevel: SecurityLevel.L2,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'ACTIVE',
    signature: 'httpRequest(url: string, method: string, headers: object, body: string): Response',
    description: '发起 HTTP/HTTPS 请求，支持 GET/POST/PUT/DELETE',
    requireApproval: false,
    inputSchema: '{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string"}}}',
    outputSchema: '{"type":"object","properties":{"status":{"type":"number"},"body":{"type":"string"}}}',
    createdAt: '2024-06-02 11:00:00',
  },
  {
    id: '3',
    toolCode: 'TOOL_FILE_READ',
    toolName: '文件读取',
    toolType: 'MCP_BOUND',
    sourceType: 'MCP',
    securityLevel: SecurityLevel.L2,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'ACTIVE',
    signature: 'readFile(path: string): FileContent',
    description: '从沙箱文件系统读取文件内容',
    requireApproval: false,
    inputSchema: '{"type":"object","properties":{"path":{"type":"string"}}}',
    outputSchema: '{"type":"object","properties":{"content":{"type":"string"}}}',
    sourceRef: 'MCP_FILE',
    createdAt: '2024-06-10 14:00:00',
  },
  {
    id: '4',
    toolCode: 'TOOL_EMAIL_SEND',
    toolName: '邮件发送',
    toolType: 'CUSTOM',
    sourceType: 'USER',
    securityLevel: SecurityLevel.L2,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'INACTIVE',
    signature: 'sendEmail(to: string[], subject: string, body: string): SendResult',
    description: '通过 SMTP 发送邮件，支持模板',
    requireApproval: true,
    inputSchema: '{"type":"object","properties":{"to":{"type":"array"},"subject":{"type":"string"},"body":{"type":"string"}}}',
    outputSchema: '{"type":"object","properties":{"success":{"type":"boolean"},"messageId":{"type":"string"}}}',
    createdAt: '2024-07-15 09:30:00',
  },
  {
    id: '5',
    toolCode: 'TOOL_TRANSLATE',
    toolName: '翻译',
    toolType: 'SKILL_BOUND',
    sourceType: 'SKILL',
    securityLevel: SecurityLevel.L1,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'ACTIVE',
    signature: 'translate(text: string, from: string, to: string): TranslateResult',
    description: '多语言互译，保留专业术语',
    requireApproval: false,
    inputSchema: '{"type":"object","properties":{"text":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"}}}',
    outputSchema: '{"type":"object","properties":{"translated":{"type":"string"}}}',
    sourceRef: 'SKILL_TRANSLATE',
    createdAt: '2024-07-20 16:00:00',
  },
  {
    id: '6',
    toolCode: 'TOOL_WEB_SEARCH',
    toolName: '网页搜索',
    toolType: 'MCP_BOUND',
    sourceType: 'MCP',
    securityLevel: SecurityLevel.L1,
    lifeStatus: LifeStatus.PUBLISHED,
    status: 'DEPRECATED',
    signature: 'webSearch(query: string, topK: number): SearchResult[]',
    description: '搜索互联网并返回结构化结果',
    requireApproval: false,
    inputSchema: '{"type":"object","properties":{"query":{"type":"string"},"topK":{"type":"number"}}}',
    outputSchema: '{"type":"array","items":{"type":"object"}}',
    sourceRef: 'MCP_SEARCH',
    createdAt: '2024-07-25 10:15:00',
  },
];

const ToolPage: React.FC = () => {
  const { message } = App.useApp();
  const [keyword, setKeyword] = useState('');
  const [toolType, setToolType] = useState<string>('all');
  const [sourceType, setSourceType] = useState<string>('all');
  const [loading, setLoading] = useState(false);
  const [tools, setTools] = useState<Tool[]>(TOOL_MOCK);

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

  /** 刷新列表 */
  const refresh = async () => {
    setLoading(true);
    try {
      const list = await toolApi.list();
      setTools(list);
      message.success('已刷新工具列表');
    } catch {
      /* 弹错已处理 */
    } finally {
      setLoading(false);
    }
  };

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
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      width: 90,
      render: (s: SourceType) => {
        const cfg = SOURCE_TYPE_TAG[s];
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
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
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '审批',
      dataIndex: 'requireApproval',
      width: 80,
      render: (v?: boolean) =>
        v ? <Tag color="orange">需要</Tag> : <Tag>无需</Tag>,
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
            <Tag color={TOOL_TYPE_TAG[r.toolType].color}>{TOOL_TYPE_TAG[r.toolType].text}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="来源">
            <Tag color={SOURCE_TYPE_TAG[r.sourceType].color}>{SOURCE_TYPE_TAG[r.sourceType].text}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="安全级别">
            <SecurityLevelTag level={r.securityLevel} />
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={TOOL_STATUS_TAG[r.status].color}>{TOOL_STATUS_TAG[r.status].text}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="生命周期">
            <LifeStatusTag status={r.lifeStatus} />
          </Descriptions.Item>
          <Descriptions.Item label="需要审批">
            {r.requireApproval ? <Tag color="orange">是</Tag> : <Tag>否</Tag>}
          </Descriptions.Item>
          {r.sourceRef && (
            <Descriptions.Item label="来源引用" span={2}>
              <Text code>{r.sourceRef}</Text>
            </Descriptions.Item>
          )}
          <Descriptions.Item label="方法签名" span={2}>
            <Text code style={{ fontSize: 12 }}>{r.signature ?? '—'}</Text>
          </Descriptions.Item>
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
                { value: 'BUILTIN', label: '内置' },
                { value: 'CUSTOM', label: '自定义' },
                { value: 'MCP_BOUND', label: 'MCP 绑定' },
                { value: 'SKILL_BOUND', label: '技能绑定' },
              ]}
            />
            <Select
              value={sourceType}
              onChange={setSourceType}
              style={{ width: 120 }}
              options={[
                { value: 'all', label: '全部来源' },
                { value: 'SYSTEM', label: '系统' },
                { value: 'USER', label: '用户' },
                { value: 'MCP', label: 'MCP' },
                { value: 'SKILL', label: '技能' },
              ]}
            />
          </Space>
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading}>
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

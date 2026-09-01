/**
 * @file JSON Schema 可视化编辑器
 * @description 以表格形式编辑 API 入参/出参的 JSON Schema，支持实时预览。
 * @author aegis
 * @since 2.0.0
 */
import React, { useMemo } from 'react';
import { Button, Table, Select, Input, Switch, Space, Tag, Typography, Divider } from 'antd';
import { PlusOutlined, DeleteOutlined, EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;

export interface SchemaPropertyRow {
  key: string;
  type: 'string' | 'number' | 'boolean' | 'object' | 'array';
  required: boolean;
  description: string;
  maxLength?: number;
  minimum?: number;
}

interface SchemaEditorProps {
  value?: string;
  onChange?: (schemaJson: string) => void;
  defaultValue?: string;
}

const TYPE_OPTIONS = [
  { value: 'string', label: 'string' },
  { value: 'number', label: 'number' },
  { value: 'boolean', label: 'boolean' },
  { value: 'object', label: 'object' },
  { value: 'array', label: 'array' },
];

const SchemaEditor: React.FC<SchemaEditorProps> = ({ value, onChange, defaultValue }) => {
  const [rows, setRows] = React.useState<SchemaPropertyRow[]>([]);
  const [showPreview, setShowPreview] = React.useState(false);

  React.useEffect(() => {
    if (value) {
      const schema = safeJsonParse<{ properties?: Record<string, Record<string, unknown>>; required?: string[] }>(value);
      if (schema?.properties) {
        const parsedRows: SchemaPropertyRow[] = Object.entries(schema.properties).map(
          ([key, prop]) => ({
            key,
            type: (prop as Record<string, unknown>).type as SchemaPropertyRow['type'] || 'string',
            required: (schema.required as string[])?.includes(key) || false,
            description: ((prop as Record<string, unknown>).description as string) || '',
            maxLength: (prop as Record<string, unknown>).maxLength as number | undefined,
            minimum: (prop as Record<string, unknown>).minimum as number | undefined,
          }),
        );
        setRows(parsedRows);
        return;
      }
    }
    if (defaultValue) {
      const schema = safeJsonParse<{ properties?: Record<string, Record<string, unknown>>; required?: string[] }>(defaultValue);
      if (schema?.properties) {
        const parsedRows: SchemaPropertyRow[] = Object.entries(schema.properties).map(
          ([key, prop]) => ({
            key,
            type: (prop as Record<string, unknown>).type as SchemaPropertyRow['type'] || 'string',
            required: (schema.required as string[])?.includes(key) || false,
            description: ((prop as Record<string, unknown>).description as string) || '',
          }),
        );
        setRows(parsedRows);
      }
    }
  }, [value, defaultValue]);

  const schemaJson = useMemo(() => {
    if (rows.length === 0) return '';
    const properties: Record<string, Record<string, unknown>> = {};
    const required: string[] = [];
    for (const row of rows) {
      const prop: Record<string, unknown> = { type: row.type };
      if (row.description) prop.description = row.description;
      if (row.type === 'string' && row.maxLength) prop.maxLength = row.maxLength;
      if (row.type === 'number' && row.minimum !== undefined) prop.minimum = row.minimum;
      properties[row.key] = prop;
      if (row.required) required.push(row.key);
    }
    const schema: Record<string, unknown> = { type: 'object', properties };
    if (required.length > 0) schema.required = required;
    return JSON.stringify(schema, null, 2);
  }, [rows]);

  React.useEffect(() => {
    if (onChange) {
      onChange(schemaJson);
    }
  }, [schemaJson, onChange]);

  const addRow = () => {
    setRows((prev) => [
      ...prev,
      { key: `param_${prev.length + 1}`, type: 'string', required: false, description: '' },
    ]);
  };

  const updateRow = (index: number, field: keyof SchemaPropertyRow, val: unknown) => {
    setRows((prev) => {
      const next = [...prev];
      next[index] = { ...next[index], [field]: val } as SchemaPropertyRow;
      return next;
    });
  };

  const deleteRow = (index: number) => {
    setRows((prev) => prev.filter((_, i) => i !== index));
  };

  const columns = [
    {
      title: '参数名',
      dataIndex: 'key',
      width: 140,
      render: (_: unknown, record: SchemaPropertyRow, idx: number) => (
        <Input
          value={record.key}
          onChange={(e) => updateRow(idx, 'key', e.target.value)}
          placeholder="参数名"
          size="small"
        />
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (_: unknown, record: SchemaPropertyRow, idx: number) => (
        <Select
          value={record.type}
          onChange={(val) => updateRow(idx, 'type', val)}
          options={TYPE_OPTIONS}
          size="small"
          style={{ width: '100%' }}
        />
      ),
    },
    {
      title: '必填',
      dataIndex: 'required',
      width: 60,
      render: (_: unknown, record: SchemaPropertyRow, idx: number) => (
        <Switch
          checked={record.required}
          onChange={(val) => updateRow(idx, 'required', val)}
          size="small"
        />
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      render: (_: unknown, record: SchemaPropertyRow, idx: number) => (
        <Input
          value={record.description}
          onChange={(e) => updateRow(idx, 'description', e.target.value)}
          placeholder="参数说明"
          size="small"
        />
      ),
    },
    {
      title: '操作',
      width: 50,
      render: (_: unknown, __: SchemaPropertyRow, idx: number) => (
        <Button
          type="text"
          danger
          size="small"
          icon={<DeleteOutlined />}
          onClick={() => deleteRow(idx)}
        />
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 8 }}>
        <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addRow}>
          添加参数
        </Button>
        <Button
          type="text"
          size="small"
          icon={showPreview ? <EyeInvisibleOutlined /> : <EyeOutlined />}
          onClick={() => setShowPreview(!showPreview)}
        >
          {showPreview ? '隐藏 Schema' : '预览 Schema'}
        </Button>
        <Tag color="blue">{rows.length} 个参数</Tag>
      </Space>

      <Table
        dataSource={rows}
        columns={columns}
        rowKey={(_, idx) => String(idx)}
        pagination={false}
        size="small"
        bordered
        locale={{ emptyText: '暂无参数，点击"添加参数"开始配置' }}
      />

      {showPreview && schemaJson && (
        <>
          <Divider style={{ margin: '12px 0' }} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            JSON Schema 预览：
          </Text>
          <pre
            style={{
              background: '#f5f5f5',
              padding: 12,
              borderRadius: 4,
              fontSize: 12,
              maxHeight: 200,
              overflow: 'auto',
              margin: '8px 0 0',
            }}
          >
            {schemaJson}
          </pre>
        </>
      )}
    </div>
  );
};

export default SchemaEditor;

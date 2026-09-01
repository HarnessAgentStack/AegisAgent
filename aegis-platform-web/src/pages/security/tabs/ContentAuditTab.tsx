/**
 * @file 内容审核 Tab
 * @description 基于敏感词数据的审核规则展示、KPI 统计
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getSensitiveWords } from '@/api/security';
import {
  AUDIT_SCOPE_MAP,
  COLOR,
  renderEnabledTag,
  WORD_ACTION_MAP,
  WORD_MATCH_MODE_MAP,
} from '../constants';
import type { SensitiveWordDTO } from '../types';

const ContentAuditTab: React.FC = () => {
  const [auditWords, setAuditWords] = useState<SensitiveWordDTO[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);

  /** 加载内容审核数据（复用敏感词） */
  const loadAuditWords = async () => {
    setAuditLoading(true);
    try {
      const res = await getSensitiveWords({ page: 1, size: 200 });
      setAuditWords((res.records || []) as SensitiveWordDTO[]);
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setAuditLoading(false);
    }
  };

  useEffect(() => {
    loadAuditWords();
  }, []);

  const total = auditWords.length;
  const blockCount = auditWords.filter((w) => w.action === 'BLOCK').length;
  const replaceCount = auditWords.filter((w) => w.action === 'REPLACE').length;
  const markCount = auditWords.filter((w) => w.action === 'MARK').length;

  const inputRules = auditWords.filter(
    (w) => w.scope === 'INPUT' || w.scope === 'ALL',
  );
  const outputRules = auditWords.filter(
    (w) => w.scope === 'OUTPUT' || w.scope === 'TOOL_RESULT' || w.scope === 'ALL',
  );

  const kpis = [
    { title: '规则总数', value: total, color: COLOR.primary },
    { title: '拦截规则', value: blockCount, color: COLOR.danger },
    { title: '替换规则', value: replaceCount, color: COLOR.success },
    { title: '标记规则', value: markCount, color: COLOR.warning },
  ];

  const auditColumns: ColumnsType<SensitiveWordDTO> = [
    { title: '规则词', dataIndex: 'word', width: 180 },
    {
      title: '作用范围',
      dataIndex: 'scope',
      width: 110,
      render: (s: string) => {
        const item = AUDIT_SCOPE_MAP[s] ?? { text: s, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 100,
      render: (a: string) => {
        const item = WORD_ACTION_MAP[a] ?? { text: a, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '匹配模式',
      dataIndex: 'matchMode',
      width: 100,
      render: (m: string) => {
        const item = WORD_MATCH_MODE_MAP[m] ?? { text: m, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    { title: '替换文本', dataIndex: 'replaceText', width: 140, render: (v?: string) => v || '-' },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (enabled?: boolean) => renderEnabledTag(enabled),
    },
  ];

  return (
    <div>
      <Row gutter={16}>
        {kpis.map((kpi) => (
          <Col key={kpi.title} xs={24} sm={12} md={6}>
            <Card>
              <Statistic
                title={kpi.title}
                value={kpi.value}
                valueStyle={{ fontWeight: 600, color: kpi.color }}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <Card title="输入审核规则" style={{ marginTop: 16 }}>
        <Table<SensitiveWordDTO>
          rowKey="id"
          columns={auditColumns}
          dataSource={inputRules}
          loading={auditLoading}
          pagination={false}
          size="middle"
          locale={{ emptyText: '暂无输入审核规则' }}
        />
      </Card>
      <Card title="输出审核规则" style={{ marginTop: 16 }}>
        <Table<SensitiveWordDTO>
          rowKey="id"
          columns={auditColumns}
          dataSource={outputRules}
          loading={auditLoading}
          pagination={false}
          size="middle"
          locale={{ emptyText: '暂无输出审核规则' }}
        />
      </Card>
    </div>
  );
};

export default ContentAuditTab;

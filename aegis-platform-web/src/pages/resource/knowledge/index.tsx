/**
 * @file 知识库管理
 * @description 知识库市场（卡片网格）+ 我的知识库（表格），双标签切换；
 *              创建/编辑知识库弹窗 + 知识库详情弹窗（5 Tab：概览/文档/切片预览/RAG配置/版本历史）
 *              文档上传采用预签名 URL 流程：申请 → PUT → 通知
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Upload,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { UploadProps } from 'antd';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import { ResourceCard } from '@/components/common/ResourceCard';
import { EmptyState } from '@/components/common/EmptyState';
import { SecurityLevelTag } from '@/components/common/SecurityLevelTag';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { LifeStatus, SecurityLevel } from '@/types/enum';
import type { KnowledgeBase, KbDocument } from '@/types/resource';
import { knowledgeApi, extractList, extractTotal } from '@/api/resource';
import { modelApi } from '@/api/model';
import type { ModelDefVO } from '@/api/model';
import type { KbChunk } from '@/types/resource';
import { useAuthStore } from '@/stores/authStore';
import { formatFileSize } from '@/utils/format';

const { Paragraph, Text } = Typography;
const { TextArea } = Input;

/** KB 卡片图标背景色 */
const ICON_BG = '#d1fae5';
/** KB 卡片图标 */
const ICON = '📚';

/** 安全级别 →卡片标签文案 / 颜色 */
const SECURITY_TAG: Record<SecurityLevel, { text: string; color: string }> = {
  [SecurityLevel.L1]: { text: 'L1 公开', color: 'green' },
  [SecurityLevel.L2]: { text: 'L2 内部', color: 'blue' },
  [SecurityLevel.L3]: { text: 'L3 机密', color: 'orange' },
  [SecurityLevel.L4]: { text: 'L4 绝密', color: 'red' },
};

/** 安全级别筛选选项 */
const SECURITY_OPTIONS: { label: string; value: string }[] = [
  { label: '全部级别', value: 'all' },
  { label: 'L1 公开', value: SecurityLevel.L1 },
  { label: 'L2 内部', value: SecurityLevel.L2 },
  { label: 'L3 机密', value: SecurityLevel.L3 },
  { label: 'L4 绝密', value: SecurityLevel.L4 },
];

/** 安全级别表单选项（不含"全部"） */
const SECURITY_FORM_OPTIONS = SECURITY_OPTIONS.filter((o) => o.value !== 'all');

/** 分块策略选项 */
const CHUNK_STRATEGY_OPTIONS = [
  { value: 'FIXED_SIZE', label: '固定大小' },
  { value: 'SENTENCE', label: '按句子' },
  { value: 'PARAGRAPH', label: '按段落' },
  { value: 'SLIDING_WINDOW', label: '滑动窗口' },
];

/** 分块策略 →文案 */
const CHUNK_STRATEGY_LABEL: Record<string, string> = {
  FIXED_SIZE: '固定大小',
  SENTENCE: '按句子',
  PARAGRAPH: '按段落',
  SLIDING_WINDOW: '滑动窗口',
};

/** 检索策略选项 */
const RETRIEVAL_STRATEGY_OPTIONS = [
  { value: 'VECTOR', label: '向量检索' },
  { value: 'KEYWORD', label: '关键词检索' },
  { value: 'HYBRID', label: '混合检索' },
];

/** 检索策略 →文案 */
const RETRIEVAL_STRATEGY_LABEL: Record<string, string> = {
  VECTOR: '向量检索',
  KEYWORD: '关键词检索',
  HYBRID: '混合检索',
};

/** 创建/编辑表单值 */
interface KbFormValues {
  kbCode: string;
  kbName: string;
  description?: string;
  securityLevel: SecurityLevel;
  chunkStrategy: string;
  chunkSize: number;
  chunkOverlap: number;
  embeddingModel: string;
  retrievalStrategy: string;
  topK: number;
  similarityThreshold: number;
  enableRerank: boolean;
  enableQueryRewrite: boolean;
}

/** 文档状态 →Tag 颜色 / 文案 */
const DOC_STATUS_MAP: Record<KbDocument['status'], { color: string; text: string }> = {
  PENDING: { color: 'default', text: '待处理' },
  SCANNING: { color: 'processing', text: '扫描中' },
  CHUNKING: { color: 'processing', text: '切片中' },
  CHUNKED: { color: 'success', text: '已切片' },
  FAILED: { color: 'error', text: '失败' },
};

// ===== 切片预览面板 =====
interface ChunksPreviewPanelProps {
  kb: KnowledgeBase;
  documents: KbDocument[];
}

const ChunksPreviewPanel: React.FC<ChunksPreviewPanelProps> = ({ kb, documents }) => {
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null);
  const [chunks, setChunks] = useState<KbChunk[]>([]);
  const [loading, setLoading] = useState(false);

  const selectedDoc = documents.find((d) => d.id === selectedDocId);
  const chunkedDocs = documents.filter((d) => d.status === 'CHUNKED');

  useEffect(() => {
    if (chunkedDocs.length > 0 && selectedDocId === null) {
      setSelectedDocId(chunkedDocs[0].id);
    }
  }, [chunkedDocs, selectedDocId]);

  useEffect(() => {
    if (selectedDocId === null || !kb?.id) return;
    const doc = documents.find((d) => d.id === selectedDocId);
    if (!doc || doc.chunkCount === 0) {
      setChunks([]);
      return;
    }

    const kbId = kb.id;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const res = await knowledgeApi.listChunks(kbId, selectedDocId, { page: 1, size: 20 });
        if (cancelled) return;
        setChunks(extractList(res));
      } catch (e) {
        if (!cancelled) {
          console.error('Failed to load chunks:', e);
          setChunks([]);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => { cancelled = true; };
  }, [selectedDocId, kb?.id]);

  if (chunkedDocs.length === 0) {
    return (
      <EmptyState
        icon={<UploadOutlined style={{ fontSize: 40, color: '#d1d5db' }} />}
        title="暂无可预览的切片"
        desc="请先上传文档并等待切片完成，切片完成后可在此预览"
      />
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
        <Card size="small" style={{ flex: '0 0 240px' }} title="文档列表">
          {chunkedDocs.map((doc) => (
            <div
              key={doc.id}
              onClick={() => setSelectedDocId(doc.id)}
              style={{
                padding: '8px 12px',
                marginBottom: 4,
                borderRadius: 6,
                cursor: 'pointer',
                background: selectedDocId === doc.id ? '#4f46e5' : 'transparent',
                color: selectedDocId === doc.id ? '#fff' : 'inherit',
                fontSize: 13,
              }}
            >
              <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {doc.fileName}
              </div>
              <div style={{ fontSize: 11, opacity: 0.75 }}>
                {doc.chunkCount ?? 0} 切片 · {formatFileSize(doc.fileSize ?? 0)}
              </div>
            </div>
          ))}
        </Card>

        <div style={{ flex: 1 }}>
          <div style={{ marginBottom: 8, fontSize: 13, color: '#6b7280' }}>
            {selectedDoc ? (
              <>
                📄 <strong>{selectedDoc.fileName}</strong> · 共 {selectedDoc.chunkCount ?? 0} 个切片（预览前 {chunks.length} 个）
              </>
            ) : (
              '请选择文档'
            )}
          </div>

          <Spin spinning={loading}>
            {chunks.length === 0 ? (
              <EmptyState
                title="该文档暂无切片"
                desc="文档可能还在切片处理中，请稍后刷新"
              />
            ) : (
              <Table
                rowKey="id"
                dataSource={chunks}
                pagination={{ pageSize: 5, size: 'small' }}
                size="small"
                columns={[
                  {
                    title: '#',
                    dataIndex: 'chunkIndex',
                    width: 50,
                    render: (v: number) => <Tag color="blue">Chunk {v}</Tag>,
                  },
                  {
                    title: '切片内容预览',
                    dataIndex: 'content',
                    render: (v: string) => (
                      <Text style={{ fontSize: 13 }}>
                        {v.length > 200 ? v.substring(0, 200) + '...' : v}
                      </Text>
                    ),
                  },
                  {
                    title: 'Token 数',
                    dataIndex: 'tokenCount',
                    width: 100,
                    render: (v: number) => <Text type="secondary">{v}</Text>,
                  },
                ]}
              />
            )}
          </Spin>
        </div>
      </div>
    </div>
  );
};

// ===== 版本历史面板 =====
/** 版本历史行 */
interface VersionRow {
  version: string;
  type: '当前版本' | '历史版本';
  changeType: 'CREATE' | 'UPDATE' | 'DELETE';
  description: string;
  operator: string;
  time?: string;
  docCount: number;
}

interface VersionHistoryPanelProps {
  kb: KnowledgeBase;
}

const VersionHistoryPanel: React.FC<VersionHistoryPanelProps> = ({ kb }) => {
  // 生成 mock 版本历史数据
  const versions = useMemo<VersionRow[]>(() => {
    const v = kb.version ?? '1';
    const versionList: VersionRow[] = [];
    const currentVer = parseInt(v, 10) || 1;

    for (let i = currentVer; i >= Math.max(1, currentVer - 4); i--) {
      versionList.push({
        version: `v${i}`,
        type: i === currentVer ? '当前版本' : '历史版本',
        changeType: i === currentVer ? 'UPDATE' : i === 1 ? 'CREATE' : 'UPDATE',
        description:
          i === currentVer
            ? '更新文档内容与切片配置'
            : i === 1
            ? '创建知识库'
            : `第 ${i} 次迭代更新`,
        operator: 'admin',
        time: i === currentVer ? kb.updatedAt ?? kb.createdAt : kb.createdAt,
        docCount: kb.docCount ?? 0,
      });
    }
    return versionList;
  }, [kb]);

  const versionColumns: ColumnsType<VersionRow> = [
    {
      title: '版本',
      dataIndex: 'version',
      width: 100,
      render: (v: string, row: VersionRow) => (
        <Space>
          <Tag color={row.type === '当前版本' ? 'green' : 'default'}>{v}</Tag>
          {row.type === '当前版本' && <Tag color="blue">最新</Tag>}
        </Space>
      ),
    },
    {
      title: '变更类型',
      dataIndex: 'changeType',
      width: 120,
      render: (t: string) => {
        const colorMap: Record<string, string> = { CREATE: 'green', UPDATE: 'blue', DELETE: 'red' };
        const labelMap: Record<string, string> = { CREATE: '创建', UPDATE: '更新', DELETE: '删除' };
        return <Tag color={colorMap[t] ?? 'default'}>{labelMap[t] ?? t}</Tag>;
      },
    },
    { title: '变更说明', dataIndex: 'description' },
    { title: '操作人', dataIndex: 'operator', width: 100 },
    { title: '文档数', dataIndex: 'docCount', width: 80 },
    { title: '变更时间', dataIndex: 'time', width: 180, render: (v?: string) => v ?? '—' },
  ];

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="版本历史说明"
        description="知识库版本记录了每次重要变更的快照，包括文档增删、配置修改等。当前展示的是基于现有数据生成的演示版本历史。"
      />

      <Table
        rowKey="version"
        columns={versionColumns}
        dataSource={versions}
        pagination={false}
        size="small"
      />

      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          💡 版本历史功能正在完善中，后续将支持版本对比与回滚操作
        </Text>
      </div>
    </div>
  );
};

const KnowledgePage: React.FC = () => {
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState<string>('market');

  // 当前用户ID（从 authStore 获取，保证与登录态一致，字符串类型避免大整数精度丢失）
  const user = useAuthStore((s) => s.user);
  const currentUserId = user?.id != null ? String(user.id) : '';

  // ===== 市场列表 =====
  const [marketKeyword, setMarketKeyword] = useState('');
  const [marketInput, setMarketInput] = useState('');
  const [marketSecurity, setMarketSecurity] = useState<string>('all');
  const [marketList, setMarketList] = useState<KnowledgeBase[]>([]);
  const [marketLoading, setMarketLoading] = useState(false);
  const [marketTotal, setMarketTotal] = useState(0);
  const [marketPage, setMarketPage] = useState(1);
  const [marketSize, setMarketSize] = useState(20);
  const [subscribed, setSubscribed] = useState<Set<string>>(new Set());

  // ===== 我的知识库列表 =====
  const [myKeyword, setMyKeyword] = useState('');
  const [myInput, setMyInput] = useState('');
  const [myList, setMyList] = useState<KnowledgeBase[]>([]);
  const [myLoading, setMyLoading] = useState(false);
  const [myTotal, setMyTotal] = useState(0);
  const [myPage, setMyPage] = useState(1);
  const [mySize, setMySize] = useState(20);

  // ===== 创建/编辑弹窗 =====
  const [formVisible, setFormVisible] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [formReady, setFormReady] = useState(false);
  const [form] = Form.useForm<KbFormValues>();

  // ===== 嵌入模型选项（动态加载，禁止前端硬编码） =====
  const [embeddingOptions, setEmbeddingOptions] = useState<{ value: string; label: string }[]>([]);

  /** 加载用户在模型管理中配置的启用 EMBEDDING 模型 */
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const list = await modelApi.listDefs();
        const options = (list ?? [])
          .filter((m: ModelDefVO) => m.modelType === 'EMBEDDING' && (m.status ?? 'DISABLED') === 'ENABLED')
          .map((m: ModelDefVO) => ({
            value: m.modelCode ?? '',
            label: m.modelName ? `${m.modelName}（${m.modelCode}）` : (m.modelCode ?? ''),
          }))
          .filter((o) => o.value !== '');
        if (!cancelled) setEmbeddingOptions(options);
      } catch (err) {
        console.error('加载嵌入模型列表失败', err);
      }
    };
    load();
    return () => { cancelled = true; };
  }, []);

  /** 当前表单嵌入模型值：若历史库绑定模型已不在可用列表（如已下线），追加兜底选项保证回显 */
  const embeddingModelValue = Form.useWatch('embeddingModel', form);
  const embeddingSelectOptions = useMemo(() => {
    if (embeddingModelValue && !embeddingOptions.some((o) => o.value === embeddingModelValue)) {
      return [...embeddingOptions, { value: embeddingModelValue, label: `${embeddingModelValue}（未启用）` }];
    }
    return embeddingOptions;
  }, [embeddingOptions, embeddingModelValue]);

  // ===== 详情弹窗 =====
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailKb, setDetailKb] = useState<KnowledgeBase | null>(null);
  const [detailTab, setDetailTab] = useState('overview');
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [docsLoading, setDocsLoading] = useState(false);

  // ===== 文档状态轮询 =====
  // B1: 加入 PENDING 态——文档上传后初始为 PENDING，等待异步流水线启动
  const hasProcessingDocs = docs.some(
    (d) => d.status === 'PENDING' || d.status === 'SCANNING' || d.status === 'CHUNKING'
  );

  useEffect(() => {
    if (!detailVisible || !detailKb?.id || !hasProcessingDocs) return;
    const timer = setInterval(async () => {
      try {
        const res = await knowledgeApi.listDocuments(detailKb.id!, { page: 1, size: 100 });
        const newDocs = extractList(res);
        setDocs(newDocs);
        // 如果没有处理中的文档，停止轮询
        const stillProcessing = newDocs.some(
          (d) => d.status === 'PENDING' || d.status === 'SCANNING' || d.status === 'CHUNKING'
        );
        if (!stillProcessing) {
          message.success('文档处理完成！');
        }
      } catch {
        // 静默失败，下次轮询继续
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [detailVisible, detailKb?.id, hasProcessingDocs]);

  /** 拉取市场列表 */
  const loadMarket = async () => {
    setMarketLoading(true);
    try {
      const res = await knowledgeApi.list({
        scope: 'market',
        keyword: marketKeyword || undefined,
        securityLevel: marketSecurity !== 'all' ? (marketSecurity as SecurityLevel) : undefined,
        page: marketPage,
        size: marketSize,
      });
      const list = extractList(res);
      setMarketList(list);
      setMarketTotal(extractTotal(res));

      // 批量查询订阅状态（单次请求替代N+1查询）
      if (list.length > 0) {
        try {
          const batchRes = await knowledgeApi.batchSubStatus(list.map((s) => s.id!));
          const subscribedSet = new Set<string>();
          if (batchRes?.subscribedMap) {
            Object.entries(batchRes.subscribedMap).forEach(([idStr, sub]) => {
              if (sub) {
                subscribedSet.add(idStr);
              }
            });
          }
          setSubscribed(subscribedSet);
        } catch {
          // 批量查询失败时降级为逐个查询
          const subscribedSet = new Set<string>();
          const results = await Promise.allSettled(
            list.map((s) => knowledgeApi.subStatus(s.id!)),
          );
          results.forEach((result, idx) => {
            if (result.status === 'fulfilled' && result.value?.subscribed) {
              subscribedSet.add(String(list[idx].id));
            }
          });
          setSubscribed(subscribedSet);
        }
      } else {
        setSubscribed(new Set());
      }
    } catch {
      /* 弹错已处理 */
    } finally {
      setMarketLoading(false);
    }
  };

  /** 拉取我的知识库列表 */
  const loadMine = async () => {
    setMyLoading(true);
    try {
      const res = await knowledgeApi.list({
        scope: 'mine',
        keyword: myKeyword || undefined,
        page: myPage,
        size: mySize,
      });
      setMyList(extractList(res));
      setMyTotal(extractTotal(res));
    } catch {
      /* 弹错已处理 */
    } finally {
      setMyLoading(false);
    }
  };

  // 切换 tab 或筛选/分页变化时加载对应列表
  useEffect(() => {
    if (activeTab === 'market') {
      loadMarket();
    } else {
      loadMine();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, marketKeyword, marketSecurity, marketPage, marketSize, myKeyword, myPage, mySize]);

  /** 切换订阅状态（调用真实 API） */
  const toggleSubscribe = async (id: number | string, isAuthor: boolean = false) => {
    const idStr = String(id);
    const alreadySubscribed = subscribed.has(idStr);

    // 如果是创建者，不允许取消订阅
    if (isAuthor && alreadySubscribed) {
      message.warning('您是该知识库的创建者，无法取消订阅');
      return;
    }

    try {
      if (!alreadySubscribed) {
        await knowledgeApi.subscribe(idStr);
        setSubscribed((prev) => {
          const next = new Set(prev);
          next.add(idStr);
          return next;
        });
        message.success('订阅成功');
      } else {
        await knowledgeApi.unsubscribe(idStr);
        setSubscribed((prev) => {
          const next = new Set(prev);
          next.delete(idStr);
          return next;
        });
        message.success('已取消订阅');
      }
      // 刷新列表以获取最新订阅数
      loadMarket();
    } catch {
      // 错误已由 http 拦截器处理
    }
  };

  /** 打开创建弹窗 */
  const openCreate = () => {
    setEditId(null);
    setFormReady(false);
    setFormVisible(true);
    form.resetFields();
    // 默认选中后端权威默认嵌入模型（若已配置启用）；否则取第一个可用嵌入模型；均无则留空由用户选择
    const preferredEmbedding =
      embeddingOptions.find((o) => o.value === 'doubao-embedding-vision')?.value ?? embeddingOptions[0]?.value;
    form.setFieldsValue({
      securityLevel: SecurityLevel.L2,
      chunkStrategy: 'PARAGRAPH',
      chunkSize: 512,
      chunkOverlap: 64,
      embeddingModel: preferredEmbedding,
      retrievalStrategy: 'HYBRID',
      topK: 5,
      similarityThreshold: 0.40,
      enableRerank: true,
      enableQueryRewrite: true,
    });
    setFormReady(true);
  };

  /** 打开编辑弹窗：先拉详情再回填 */
  const openEdit = async (record: KnowledgeBase) => {
    setEditId(record.id ?? null);
    setFormVisible(true);
    setFormReady(false);
    setFormLoading(true);
    form.resetFields();
    try {
      const full = await knowledgeApi.detail(record.id!);
      form.setFieldsValue({
        kbCode: full.kbCode,
        kbName: full.kbName,
        description: full.description,
        securityLevel: full.securityLevel,
        chunkStrategy: full.chunkStrategy ?? 'PARAGRAPH',
        chunkSize: full.chunkSize ?? 512,
        chunkOverlap: full.chunkOverlap ?? 64,
        embeddingModel: full.embeddingModel,
        retrievalStrategy: full.retrievalStrategy ?? 'HYBRID',
        topK: full.topK ?? 5,
        similarityThreshold: full.similarityThreshold ?? 0.40,
        enableRerank: full.enableRerank ?? true,
        enableQueryRewrite: full.enableQueryRewrite ?? true,
      });
    } catch {
      // 失败时用列表 record 兜底回填
      form.setFieldsValue({
        kbCode: record.kbCode,
        kbName: record.kbName,
        description: record.description,
        securityLevel: record.securityLevel,
        chunkStrategy: record.chunkStrategy ?? 'PARAGRAPH',
        chunkSize: record.chunkSize ?? 512,
        chunkOverlap: record.chunkOverlap ?? 64,
        embeddingModel: record.embeddingModel,
        retrievalStrategy: record.retrievalStrategy ?? 'HYBRID',
        topK: record.topK ?? 5,
        similarityThreshold: record.similarityThreshold ?? 0.40,
        enableRerank: record.enableRerank ?? true,
        enableQueryRewrite: record.enableQueryRewrite ?? true,
      });
    } finally {
      setFormLoading(false);
      setFormReady(true);
    }
  };

  /** 提交创建/编辑 */
  const submitForm = async () => {
    try {
      const values = await form.validateFields();
      setFormLoading(true);
      const payload: KnowledgeBase = {
        kbCode: values.kbCode,
        kbName: values.kbName,
        description: values.description,
        securityLevel: values.securityLevel,
        chunkStrategy: values.chunkStrategy,
        chunkSize: values.chunkSize,
        chunkOverlap: values.chunkOverlap,
        embeddingModel: values.embeddingModel,
        retrievalStrategy: values.retrievalStrategy,
        topK: values.topK,
        similarityThreshold: values.similarityThreshold,
        enableRerank: values.enableRerank,
        enableQueryRewrite: values.enableQueryRewrite,
      };
      if (editId !== null) {
        await knowledgeApi.update(editId, { ...payload, id: editId });
        message.success('知识库配置更新成功');
      } else {
        await knowledgeApi.create(payload);
        message.success('知识库创建成功，当前为草稿态');
      }
      setFormVisible(false);
      // 创建/编辑成功后刷新列表
      if (activeTab === 'mine') {
        loadMine();
      } else {
        loadMarket();
      }
    } catch (err) {
      console.error(err);
    } finally {
      setFormLoading(false);
    }
  };

  /** 提交审核 */
  const submitForReview = async (record: KnowledgeBase) => {
    try {
      await knowledgeApi.submitReview(record.id!);
      message.success('已提交审核，等待审核人处理');
      loadMine();
    } catch {
      /* 弹错已处理 */
    }
  };

  /** 删除知识库 */
  const deleteKb = async (record: KnowledgeBase) => {
    try {
      await knowledgeApi.remove(record.id!);
      message.success('知识库已删除');
      loadMine();
    } catch {
      /* 弹错已处理 */
    }
  };

  /** 拉取文档列表 */
  const loadDocuments = async (kbId: string) => {
    setDocsLoading(true);
    try {
      const res = await knowledgeApi.listDocuments(kbId, { page: 1, size: 100 });
      setDocs(extractList(res));
    } catch {
      /* 弹错已处理 */
    } finally {
      setDocsLoading(false);
    }
  };

  /** 打开详情弹窗：拉取完整详情与文档列表 */
  const openDetail = async (record: KnowledgeBase) => {
    setDetailVisible(true);
    setDetailKb(null);
    setDetailTab('overview');
    setDocs([]);
    setDetailLoading(true);
    try {
      const full = await knowledgeApi.detail(record.id!);
      setDetailKb(full);
      // 顺便预载文档列表
      if (full.id != null) {
        loadDocuments(full.id);
      }
    } catch {
      // 失败时用列表 record 兜底展示
      setDetailKb(record);
      if (record.id != null) {
        loadDocuments(record.id);
      }
    } finally {
      setDetailLoading(false);
    }
  };

  /** 直接上传（推荐）：通过后端代理上传文件 */
  const customUpload: UploadProps['customRequest'] = async (options) => {
    const { file, onSuccess, onError, onProgress } = options;
    const kbId = detailKb?.id;
    if (kbId == null) {
      onError?.(new Error('未选中知识库'));
      return;
    }
    const rawFile = file as File;
    try {
      // 使用直接上传接口（后端代理）
      const result = await knowledgeApi.uploadFile(kbId, rawFile);
      onSuccess?.(result);
      message.success(`${rawFile.name} 上传成功，已开始切片`);
      // 刷新文档列表
      loadDocuments(kbId);
    } catch (err) {
      console.error('文件上传失败，尝试使用预签名URL上传:', err);
      // 如果直接上传失败，降级到预签名URL流程
      try {
        const applyRes = await knowledgeApi.uploadApply(kbId, {
          fileName: rawFile.name,
          fileSize: rawFile.size,
        });
        await new Promise<void>((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          xhr.open('PUT', applyRes.uploadUrl, true);
          xhr.upload.onprogress = (e) => {
            if (e.lengthComputable) {
              onProgress?.({ percent: Math.round((e.loaded / e.total) * 100) }, file as never);
            }
          };
          xhr.onload = () =>
            xhr.status >= 200 && xhr.status < 300
              ? resolve()
              : reject(new Error(`上传失败：HTTP ${xhr.status}`));
          xhr.onerror = () => reject(new Error('网络异常，上传失败'));
          xhr.send(rawFile);
        });
        await knowledgeApi.uploadNotify(kbId, { objectKey: applyRes.objectKey });
        onSuccess?.(applyRes);
        message.success(`${rawFile.name} 上传成功，已开始切片`);
        loadDocuments(kbId);
      } catch (err2) {
        onError?.(err2 as Error);
      }
    }
  };

  /** 删除文档 */
  const deleteDoc = async (docId: string) => {
    const kbId = detailKb?.id;
    if (kbId == null) return;
    try {
      await knowledgeApi.deleteDocument(kbId, docId);
      message.success('文档已删除');
      loadDocuments(kbId);
    } catch {
      /* 弹错已处理 */
    }
  };

  // ===== 我的知识库表格列 =====
  const myColumns: ColumnsType<KnowledgeBase> = [
    { title: '知识库编码', dataIndex: 'kbCode', width: 160 },
    { title: '名称', dataIndex: 'kbName' },
    {
      title: '安全级别',
      dataIndex: 'securityLevel',
      width: 120,
      render: (level: SecurityLevel) => <SecurityLevelTag level={level} />,
    },
    {
      title: '状态',
      dataIndex: 'lifeStatus',
      width: 110,
      render: (status?: LifeStatus) => (status ? <LifeStatusTag status={status} /> : '—'),
    },
    { title: '文档数', dataIndex: 'docCount', width: 90, render: (v?: number) => v ?? 0 },
    { title: '版本', dataIndex: 'version', width: 100, render: (v?: string) => (v ? `v${v}` : '—') },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (v?: string) => v ?? '—',
    },
    {
      title: '操作',
      width: 280,
      fixed: 'right',
      render: (_: unknown, record: KnowledgeBase) => {
        const isDraft = record.lifeStatus === LifeStatus.DRAFT;
        return (
          <Space size="small" wrap>
            <a onClick={() => openDetail(record)}>
              <EyeOutlined /> 详情
            </a>
            <a
              onClick={() => openEdit(record)}
              style={!isDraft ? { color: '#d1d5db', cursor: 'not-allowed' } : undefined}
            >
              <EditOutlined /> 编辑
            </a>
            {isDraft && (
              <Popconfirm
                title="确认提交审核？"
                description="提交后字段将冻结，等待审核人处理"
                onConfirm={() => submitForReview(record)}
              >
                <a style={{ color: '#4f46e5' }}>
                  <SendOutlined /> 提交审核
                </a>
              </Popconfirm>
            )}
            {isDraft && (
              <Popconfirm
                title={`确认删除「${record.kbName}」？`}
                description="删除后不可恢复"
                onConfirm={() => deleteKb(record)}
              >
                <a style={{ color: '#ff4d4f' }}>
                  <DeleteOutlined /> 删除
                </a>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  // ===== 文档表格列 =====
  const reprocessDoc = async (docId: string) => {
    if (!detailKb?.id) return;
    try {
      await knowledgeApi.reprocessDocument(detailKb.id, docId);
      message.success('已重新提交处理');
      loadDocuments(detailKb.id);
    } catch {
      /* 错误已处理 */
    }
  };

  const docColumns: ColumnsType<KbDocument> = [
    { title: '文件名', dataIndex: 'fileName' },
    {
      title: '类型',
      dataIndex: 'fileType',
      width: 90,
      render: (t?: string) => (t ? <Tag>{t.toUpperCase()}</Tag> : '—'),
    },
    {
      title: '大小',
      dataIndex: 'fileSize',
      width: 110,
      render: (s: number) => formatFileSize(s ?? 0),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: KbDocument['status']) => {
        const cfg = DOC_STATUS_MAP[s] ?? DOC_STATUS_MAP.PENDING;
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '切片数', dataIndex: 'chunkCount', width: 90, render: (v?: number) => v ?? 0 },
    {
      title: '上传时间',
      dataIndex: 'uploadedAt',
      width: 170,
      render: (v?: string) => v ?? '—',
    },
    {
      title: '操作',
      width: 140,
      render: (_: unknown, record: KbDocument) => (
        <Space size={4}>
          {record.status === 'FAILED' && (
            <a
              style={{ color: '#fa8c16', fontSize: 12 }}
              onClick={() => reprocessDoc(record.id)}
            >
              重新处理
            </a>
          )}
          <Popconfirm title={`确认删除「${record.fileName}」？`} onConfirm={() => deleteDoc(record.id)}>
            <a style={{ color: '#ff4d4f', fontSize: 12 }}>
              删除
            </a>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  /** 渲染创建/编辑弹窗 */
  const renderFormModal = () => (
    <Modal
      title={editId !== null ? '编辑知识库' : '创建知识库'}
      open={formVisible}
      onCancel={() => setFormVisible(false)}
      onOk={submitForm}
      confirmLoading={formLoading}
      width={820}
      okText={editId !== null ? '保存' : '创建'}
      destroyOnClose
    >
      <Spin spinning={formLoading && !formReady}>
        <Form<KbFormValues> form={form} layout="vertical" disabled={!formReady}>
          {/* 基本信息 */}
          <Card size="small" title="基本信息" style={{ marginBottom: 16 }}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="kbCode"
                  label="知识库编码"
                  rules={[
                    { required: true, message: '请输入知识库编码' },
                    { pattern: /^[A-Z][A-Z0-9_-]{2,31}$/, message: '大写字母开头，3-32 字符' },
                  ]}
                  tooltip="租户内唯一，创建后不可修改"
                >
                  <Input placeholder="如 KB_PRODUCT_DOC" disabled={editId !== null} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="kbName"
                  label="知识库名称"
                  rules={[{ required: true, message: '请输入名称' }]}
                >
                  <Input placeholder="如 产品文档库" />
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="description" label="描述">
                  <TextArea rows={2} placeholder="知识库用途与范围说明" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="securityLevel" label="安全级别" rules={[{ required: true }]}>
                  <Select options={SECURITY_FORM_OPTIONS} />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          {/* 切片配置 */}
          <Card size="small" title="切片配置" style={{ marginBottom: 16 }}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="chunkStrategy" label="分块策略" rules={[{ required: true }]}>
                  <Select options={CHUNK_STRATEGY_OPTIONS} />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="chunkSize" label="分块大小" rules={[{ required: true }]}>
                  <InputNumber min={64} max={4096} step={64} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="chunkOverlap" label="分块重叠" rules={[{ required: true }]}>
                  <InputNumber min={0} max={512} step={16} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </Card>

          {/* 检索配置 */}
          <Card size="small" title="检索配置">
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="embeddingModel"
                  label="嵌入模型"
                  rules={[{ required: true, message: '请选择嵌入模型' }]}
                  extra={
                    embeddingOptions.length === 0
                      ? '暂无可用嵌入模型，请先在「模型管理」中配置并启用 EMBEDDING 类型模型'
                      : undefined
                  }
                >
                  <Select
                    options={embeddingSelectOptions}
                    showSearch
                    optionFilterProp="label"
                    placeholder="请选择嵌入模型"
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="retrievalStrategy" label="检索策略" rules={[{ required: true }]}>
                  <Select options={RETRIEVAL_STRATEGY_OPTIONS} />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="topK" label="Top K" rules={[{ required: true }]}>
                  <InputNumber min={1} max={20} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item
                  name="similarityThreshold"
                  label="相似度阈值"
                  rules={[{ required: true }]}
                  extra="低于该分数的检索片段将被丢弃；阈值过高会过滤掉全部检索结果"
                  tooltip="COSINE 相似度的量纲因嵌入模型而异：doubao-embedding 系列相关内容相似度通常在 0.3~0.6 区间，建议 0.40；BGE 系列通常在 0.6~0.85 区间，建议 0.75"
                >
                  <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="enableRerank" label="启用重排序" valuePropName="checked">
                  <Switch checkedChildren="开" unCheckedChildren="关" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="enableQueryRewrite" label="启用查询改写" valuePropName="checked">
                  <Switch checkedChildren="开" unCheckedChildren="关" />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        </Form>
      </Spin>
    </Modal>
  );

  /** 渲染详情弹窗 */
  const renderDetailModal = () => {
    const kb = detailKb;
    const isKbAuthor = detailKb?.authorUserId != null && String(detailKb.authorUserId) === String(currentUserId);

    // 非创建者仅展示概览 Tab
    const allTabs = [
      {
        key: 'overview',
        label: '📋 概览',
        children: (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="知识库编码">{kb?.kbCode}</Descriptions.Item>
            <Descriptions.Item label="名称">{kb?.kbName}</Descriptions.Item>
            <Descriptions.Item label="安全级别">
              {kb?.securityLevel ? <SecurityLevelTag level={kb.securityLevel} /> : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="版本">
              {kb?.version ? `v${kb.version}` : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="文档数">{kb?.docCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="状态">
              {kb?.lifeStatus ? <LifeStatusTag status={kb.lifeStatus} /> : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="订阅数">{kb?.subsCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="可见范围">
              {kb?.visibility === 'PUBLIC' ? '公开' : kb?.visibility === 'TENANT' ? '租户内' : kb?.visibility ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">{kb?.createdAt ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="描述" span={2}>
              {kb?.description ?? '—'}
            </Descriptions.Item>
          </Descriptions>
        ),
      },
    ];

    // 仅作者可见的 Tab：文档管理、切片预览、RAG配置、版本历史
    if (isKbAuthor && kb) {
      allTabs.push(
        {
          key: 'docs',
          label: '📄 文档',
          children: (
            <div>
              <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Paragraph type="secondary" style={{ fontSize: 12, margin: 0 }}>
                  支持 PDF / DOCX / MD / TXT 格式，单文件不超过 50MB。上传后系统将自动进行安全扫描与切片处理。
                  {hasProcessingDocs && (
                    <Tag color="processing" style={{ marginLeft: 8 }}>
                      处理中... 自动刷新
                    </Tag>
                  )}
                </Paragraph>
                <Button
                  size="small"
                  icon={<ReloadOutlined />}
                  onClick={() => detailKb?.id && loadDocuments(detailKb.id)}
                  loading={docsLoading}
                >
                  刷新
                </Button>
              </div>
              <Upload.Dragger
                multiple
                accept=".pdf,.docx,.md,.txt"
                customRequest={customUpload}
                showUploadList
              >
                <p className="ant-upload-drag-icon">
                  <UploadOutlined style={{ fontSize: 40, color: '#4f46e5' }} />
                </p>
                <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
                <p className="ant-upload-hint">支持单个或批量上传</p>
              </Upload.Dragger>
              <Table<KbDocument>
                rowKey="id"
                columns={docColumns}
                dataSource={docs}
                loading={docsLoading}
                pagination={{ defaultPageSize: 10, showSizeChanger: true }}
                size="small"
              />
            </div>
          ),
        },
        {
          key: 'chunks',
          label: '🔬 切片预览',
          children: <ChunksPreviewPanel kb={kb} documents={docs} />,
        },
        {
          key: 'rag',
          label: '⚙️ RAG配置',
          children: (
            <Descriptions column={2} bordered size="small" title="检索增强配置">
              <Descriptions.Item label="检索策略">
                {RETRIEVAL_STRATEGY_LABEL[kb.retrievalStrategy ?? ''] ?? kb.retrievalStrategy ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="嵌入模型">
                {kb.embeddingModel ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="Top K">{kb.topK ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="相似度阈值">
                {typeof kb.similarityThreshold === 'number' ? kb.similarityThreshold : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="分块策略">
                {CHUNK_STRATEGY_LABEL[kb.chunkStrategy ?? ''] ?? kb.chunkStrategy ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="分块大小">{kb.chunkSize ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="分块重叠">{kb.chunkOverlap ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="启用重排序">
                <Tag color={kb.enableRerank ? 'green' : 'default'}>
                  {kb.enableRerank ? '是' : '否'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="启用查询改写">
                <Tag color={kb.enableQueryRewrite ? 'green' : 'default'}>
                  {kb.enableQueryRewrite ? '是' : '否'}
                </Tag>
              </Descriptions.Item>
            </Descriptions>
          ),
        },
        {
          key: 'versions',
          label: '🕰 版本历史',
          children: <VersionHistoryPanel kb={kb} />,
        },
      );
    }

    return (
      <Modal
        title={kb ? `知识库详情 - ${kb.kbName}` : '知识库详情'}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={960}
        destroyOnClose
      >
        <Spin spinning={detailLoading}>
          {kb && (
            <Tabs
              activeKey={detailTab}
              onChange={setDetailTab}
              items={allTabs}
            />
          )}
        </Spin>
      </Modal>
    );
  };

  return (
    <div>
      <PageHeader title="知识库市场" desc="知识库市场 · 订阅向量知识库，管理你的文档集合" />
      <BigTabs
        tabs={[
          { key: 'market', label: '🏪 知识库市场', badge: marketTotal },
          { key: 'mine', label: '📦 我的知识库', badge: myTotal },
        ]}
        active={activeTab}
        onChange={(key) => {
          setActiveTab(key);
          if (key === 'market') {
            setMarketPage(1);
          } else {
            setMyPage(1);
          }
        }}
      />

      {activeTab === 'market' ? (
        <>
          <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
            <Space>
              <Input.Search
                placeholder="搜索知识库"
                value={marketInput}
                onChange={(e) => {
                  setMarketInput(e.target.value);
                  if (e.target.value === '') {
                    setMarketKeyword('');
                    setMarketPage(1);
                  }
                }}
                onSearch={(v) => {
                  setMarketKeyword(v);
                  setMarketPage(1);
                }}
                allowClear
                style={{ width: 240 }}
                enterButton
              />
              <Select
                value={marketSecurity}
                onChange={(v) => {
                  setMarketSecurity(v);
                  setMarketPage(1);
                }}
                options={SECURITY_OPTIONS}
                style={{ width: 140 }}
              />
            </Space>
          </Space>

          <Spin spinning={marketLoading}>
            {marketList.length === 0 && !marketLoading ? (
              <EmptyState title="暂无知识库" desc="未找到符合条件的知识库，试试调整筛选条件" />
            ) : (
              <Row gutter={[16, 16]}>
                {marketList.map((item) => {
                  const itemId = String(item.id ?? '');
                  const isAuthor = String(item.authorUserId) === String(currentUserId);
                  const isSubscribed = subscribed.has(itemId) || isAuthor;
                  return (
                    <Col key={itemId} xs={24} sm={12} lg={6}>
                      <ResourceCard
                        icon={item.icon || ICON}
                        iconBg={ICON_BG}
                        name={item.kbName}
                        desc={item.description ?? '—'}
                        meta={[
                          { label: '文档', value: String(item.docCount ?? 0) },
                          { label: '版本', value: item.version ? `v${item.version}` : '—' },
                        ]}
                        tags={[SECURITY_TAG[item.securityLevel]]}
                        actions={[
                          <Button
                            key="sub"
                            size="small"
                            type={isSubscribed ? 'default' : 'primary'}
                            disabled={isAuthor}
                            onClick={() => toggleSubscribe(itemId, isAuthor)}
                          >
                            {isAuthor ? '已创建' : isSubscribed ? '已订阅' : '订阅'}
                          </Button>,
                          <Button
                            key="detail"
                            size="small"
                            onClick={() => openDetail(item)}
                          >
                            详情
                          </Button>,
                        ]}
                      />
                    </Col>
                  );
                })}
              </Row>
            )}
            {marketTotal > 0 && (
              <div style={{ marginTop: 16, textAlign: 'right' }}>
                <Pagination
                  current={marketPage}
                  pageSize={marketSize}
                  total={marketTotal}
                  showSizeChanger
                  showTotal={(t) => `共 ${t} 条`}
                  onChange={(p, sz) => {
                    setMarketPage(p);
                    setMarketSize(sz);
                  }}
                />
              </div>
            )}
          </Spin>
        </>
      ) : (
        <>
          <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
            <Input.Search
              placeholder="搜索知识库编码/名称"
              value={myInput}
              onChange={(e) => {
                setMyInput(e.target.value);
                if (e.target.value === '') {
                  setMyKeyword('');
                  setMyPage(1);
                }
              }}
              onSearch={(v) => {
                setMyKeyword(v);
                setMyPage(1);
              }}
              allowClear
              style={{ width: 240 }}
              enterButton
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              ➕ 创建
            </Button>
          </div>
          <Table<KnowledgeBase>
            rowKey="id"
            columns={myColumns}
            dataSource={myList}
            loading={myLoading}
            scroll={{ x: 1200 }}
            pagination={{
              current: myPage,
              pageSize: mySize,
              total: myTotal,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, sz) => {
                setMyPage(p);
                setMySize(sz);
              },
            }}
          />
        </>
      )}

      {renderFormModal()}
      {renderDetailModal()}
    </div>
  );
};

export default KnowledgePage;

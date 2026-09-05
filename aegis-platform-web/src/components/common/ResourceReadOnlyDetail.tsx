/**
 * @file 通用只读资源详情组件
 * @description 用于审核页面展示各类资源的只读详情，支持：智能体、SKILL、知识库、MCP
 *              复用各资源类型的详情展示逻辑，但以只读模式呈现
 * @author aegis
 * @since 2.0.0
 */
import React, { useEffect, useState } from 'react';
import { Card, Descriptions, Spin, Tag, Typography, Empty, Space, Avatar, Divider, Alert } from 'antd';
import { ThunderboltOutlined, DatabaseOutlined, ApiOutlined, ToolOutlined } from '@ant-design/icons';
import type { Agent, AgentBindingVO } from '@/types/agent';
import type { Skill, KnowledgeBase, McpServer, Tool, KbDocument } from '@/types/resource';
import { getAgentDetail } from '@/api/agent';
import { skillApi, knowledgeApi, mcpApi, toolApi } from '@/api/resource';
import { SecurityLevelTag } from '@/components/common/SecurityLevelTag';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { formatFileSize } from '@/utils/format';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;

/** 组件属性 */
interface ResourceReadOnlyDetailProps {
  /** 资源类型 */
  resourceType: 'AGENT' | 'SKILL' | 'KNOWLEDGE_BASE' | 'MCP' | 'MCP_SERVICE' | 'TOOL';
  /** 资源ID */
  resourceId: number | string;
  /** 基础信息（来自审核单） */
  basicInfo?: {
    resourceName?: string;
    version?: string;
    securityLevel?: number;
    changeSummary?: string;
  };
}

const ResourceReadOnlyDetail: React.FC<ResourceReadOnlyDetailProps> = ({
  resourceType,
  resourceId,
  basicInfo,
}) => {
  const [loading, setLoading] = useState(false);
  const [agentDetail, setAgentDetail] = useState<Agent | null>(null);
  const [skillDetail, setSkillDetail] = useState<Skill | null>(null);
  const [kbDetail, setKbDetail] = useState<KnowledgeBase | null>(null);
  const [mcpDetail, setMcpDetail] = useState<McpServer | null>(null);
  const [toolDetail, setToolDetail] = useState<Tool | null>(null);
  const [kbDocs, setKbDocs] = useState<KbDocument[]>([]);

  useEffect(() => {
    const loadDetail = async () => {
      setLoading(true);
      try {
        const idStr = String(resourceId);
        
        if (resourceType === 'AGENT') {
          const data = await getAgentDetail(idStr);
          setAgentDetail(data);
        } else if (resourceType === 'SKILL') {
          if (idStr) {
            const data = await skillApi.detail(idStr);
            setSkillDetail(data);
          }
        } else if (resourceType === 'KNOWLEDGE_BASE') {
          if (idStr) {
            const data = await knowledgeApi.detail(idStr);
            setKbDetail(data);
            // 同时加载文档列表
            try {
              const docsRes = await knowledgeApi.listDocuments(idStr, { page: 1, size: 20 });
              setKbDocs(Array.isArray(docsRes) ? docsRes : docsRes?.records ?? []);
            } catch {
              // 静默处理
            }
          }
        } else if (resourceType === 'MCP' || resourceType === 'MCP_SERVICE') {
          if (idStr) {
            // MCP 详情通过 listServices 获取
            const res = await mcpApi.listServices({ page: 1, size: 100 });
            const list = Array.isArray(res) ? res : res?.records ?? [];
            const found = list.find((s: McpServer) => s.id === idStr);
            if (found) {
              setMcpDetail(found);
            }
          }
        } else if (resourceType === 'TOOL') {
          if (idStr) {
            // 工具详情通过 list 获取后筛选
            const list = await toolApi.list();
            const found = list.find((t: Tool) => t.id === idStr);
            if (found) {
              setToolDetail(found);
            }
          }
        }
      } catch (err) {
        console.error('加载资源详情失败:', err);
      } finally {
        setLoading(false);
      }
    };

    if (resourceId) {
      loadDetail();
    }
  }, [resourceType, resourceId]);

  /** 渲染智能体详情 */
  const renderAgentDetail = (agent: Agent) => (
    <div>
      {/* 基本信息卡片 */}
      <Card style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Avatar
            size={56}
            style={{ background: agent.color || '#4f46e5', fontSize: 24 }}
          >
            {agent.icon || '🤖'}
          </Avatar>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 6 }}>
              {agent.agentName}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <Tag color={agent.lifeStatus === 'PUBLISHED' ? 'green' : agent.lifeStatus === 'REVIEWING' ? 'processing' : 'default'}>
                {agent.lifeStatus || '-'}
              </Tag>
              <Tag>{agent.version || 'v0.0.1'}</Tag>
              <Tag color={agent.governanceTier === 'STRICT' ? 'red' : agent.governanceTier === 'ENHANCED' ? 'gold' : 'green'}>
                {agent.governanceTier || '标准档'}
              </Tag>
            </div>
          </div>
        </div>
      </Card>

      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="智能体编码">
          <Text code>{agent.agentCode || '-'}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="智能体类型">
          {agent.agentType === 'APPLICATION' ? '应用智能体' : agent.agentType === 'UNIVERSAL' ? '通用智能体' : agent.agentType === 'SYSTEM' ? '系统智能体' : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="模型档位">{agent.modelTier || '-'}</Descriptions.Item>
        <Descriptions.Item label="可见性">{agent.visibility || 'TENANT'}</Descriptions.Item>
        <Descriptions.Item label="订阅数">{agent.subsCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="描述" span={2}>
          {agent.description || '-'}
        </Descriptions.Item>
        {agent.category && (
          <Descriptions.Item label="分类">
            {agent.category}
          </Descriptions.Item>
        )}
      </Descriptions>

      {agent.bindings && agent.bindings.length > 0 && (
        <Card size="small" title="资源绑定" style={{ marginTop: 12 }}>
          <Space size={[8, 8]} wrap>
            {agent.bindings.map((b: AgentBindingVO, i: number) => (
              <Tag key={b.id ?? i} icon={<ToolOutlined />}>
                {b.resourceType || 'UNKNOWN'} · {b.resourceId}
              </Tag>
            ))}
          </Space>
        </Card>
      )}

      {basicInfo?.changeSummary && (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 12 }}
          message="变更说明"
          description={basicInfo.changeSummary}
        />
      )}
    </div>
  );

  /** 渲染技能详情 */
  const renderSkillDetail = (skill: Skill) => {
    const tags = skill.tags ? (safeJsonParse<string[]>(skill.tags) ?? []) : [];
    const tools = skill.bindingTools ? (safeJsonParse<string[]>(skill.bindingTools) ?? []) : [];
    
    return (
      <div>
        <Card style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Avatar size={48} style={{ background: '#e6f4ff', color: '#1677ff' }}>
              <ThunderboltOutlined style={{ fontSize: 24 }} />
            </Avatar>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
                {skill.skillName}
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <Tag color={skill.skillType === 'COMPOSITE' ? 'purple' : 'blue'}>
                  {skill.skillType === 'COMPOSITE' ? '组合技能' : '原子技能'}
                </Tag>
                {skill.lifeStatus && <LifeStatusTag status={skill.lifeStatus} />}
                <Tag>{skill.version || '-'}</Tag>
              </div>
            </div>
          </div>
        </Card>

        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="技能编码">
            <Text code>{skill.skillCode}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="分类">{skill.category || '-'}</Descriptions.Item>
          <Descriptions.Item label="安全级别">
            <SecurityLevelTag level={skill.securityLevel} />
          </Descriptions.Item>
          <Descriptions.Item label="可见范围">
            {skill.visibility === 'PUBLIC' ? '公开' : skill.visibility === 'TENANT' ? '租户内' : skill.visibility || '-'}
          </Descriptions.Item>
          {skill.scope && (
            <Descriptions.Item label="作用域">
              <Tag color={skill.scope === 'GLOBAL' ? 'purple' : 'blue'}>
                {skill.scope === 'GLOBAL' ? '全局' : '局部'}
              </Tag>
            </Descriptions.Item>
          )}
          <Descriptions.Item label="订阅数">{skill.subsCount ?? 0}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>
            {skill.description || '-'}
          </Descriptions.Item>
          {skill.instructions && (
            <Descriptions.Item label="方法论/指令" span={2}>
              <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, maxHeight: 200, overflow: 'auto', fontSize: 12, margin: 0 }}>
                {skill.instructions}
              </pre>
            </Descriptions.Item>
          )}
          {tags.length > 0 && (
            <Descriptions.Item label="标签" span={2}>
              <Space size={[4, 4]} wrap>
                {tags.map((t: string) => <Tag key={t}>{t}</Tag>)}
              </Space>
            </Descriptions.Item>
          )}
        </Descriptions>

        {tools.length > 0 && (
          <Card size="small" title="绑定工具" style={{ marginTop: 12 }}>
            <Space size={[8, 8]} wrap>
              {tools.map((t: string) => (
                <Tag key={t} color="cyan">{t}</Tag>
              ))}
            </Space>
          </Card>
        )}

        {basicInfo?.changeSummary && (
          <Alert type="info" showIcon style={{ marginTop: 12 }} message="变更说明" description={basicInfo.changeSummary} />
        )}
      </div>
    );
  };

  /** 渲染知识库详情 */
  const renderKbDetail = (kb: KnowledgeBase) => (
    <div>
      <Card style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Avatar size={48} style={{ background: '#d1fae5', color: '#059669' }}>
            <DatabaseOutlined style={{ fontSize: 24 }} />
          </Avatar>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
              {kb.kbName}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {kb.lifeStatus && <LifeStatusTag status={kb.lifeStatus} />}
              <Tag>{kb.version || '-'}</Tag>
              <Tag>文档 {kb.docCount ?? 0}</Tag>
            </div>
          </div>
        </div>
      </Card>

      <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
        <Descriptions.Item label="知识库编码">
          <Text code>{kb.kbCode}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="安全级别">
          <SecurityLevelTag level={kb.securityLevel} />
        </Descriptions.Item>
        <Descriptions.Item label="可见范围">
          {kb.visibility === 'PUBLIC' ? '公开' : kb.visibility === 'TENANT' ? '租户内' : kb.visibility || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="订阅数">{kb.subsCount ?? 0}</Descriptions.Item>
        <Descriptions.Item label="创建时间">{kb.createdAt || '-'}</Descriptions.Item>
        <Descriptions.Item label="描述" span={2}>
          {kb.description || '-'}
        </Descriptions.Item>
      </Descriptions>

      <Descriptions column={2} size="small" bordered title="检索配置">
        <Descriptions.Item label="分块策略">
          {kb.chunkStrategy ? { FIXED: '固定大小', FIXED_SIZE: '固定大小', SENTENCE: '按句子', PARAGRAPH: '按段落', MARKDOWN: 'Markdown 标题', SLIDING_WINDOW: '滑动窗口' }[kb.chunkStrategy] : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="分块大小">{kb.chunkSize || '-'}</Descriptions.Item>
        <Descriptions.Item label="分块重叠">{kb.chunkOverlap ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="嵌入模型">{kb.embeddingModel || '-'}</Descriptions.Item>
        <Descriptions.Item label="检索策略">
          {kb.retrievalStrategy ? { VECTOR: '向量检索', KEYWORD: '关键词检索', HYBRID: '混合检索' }[kb.retrievalStrategy] : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="Top K">{kb.topK ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="相似度阈值">
          {typeof kb.similarityThreshold === 'number' ? kb.similarityThreshold : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="启用重排序">
          <Tag color={kb.enableRerank ? 'green' : 'default'}>{kb.enableRerank ? '是' : '否'}</Tag>
        </Descriptions.Item>
      </Descriptions>

      {kbDocs.length > 0 && (
        <Card size="small" title={`文档列表 (${kbDocs.length})`} style={{ marginTop: 12 }}>
          {kbDocs.slice(0, 10).map((doc: KbDocument) => (
            <div key={doc.id} style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0', borderBottom: '1px solid #f0f0f0' }}>
              <span>{doc.fileName}</span>
              <span style={{ color: '#999', fontSize: 12 }}>
                {doc.fileType?.toUpperCase()} · {formatFileSize(doc.fileSize || 0)} · 切片 {doc.chunkCount ?? 0}
              </span>
            </div>
          ))}
          {kbDocs.length > 10 && (
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
              还有 {kbDocs.length - 10} 个文档...
            </Text>
          )}
        </Card>
      )}

      {basicInfo?.changeSummary && (
        <Alert type="info" showIcon style={{ marginTop: 12 }} message="变更说明" description={basicInfo.changeSummary} />
      )}
    </div>
  );

  /** 渲染MCP详情 */
  const renderMcpDetail = (mcp: McpServer) => (
    <div>
      <Card style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Avatar size={48} style={{ background: '#e0e7ff', color: '#4f46e5' }}>
            <ApiOutlined style={{ fontSize: 24 }} />
          </Avatar>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
              {mcp.mcpName}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <Tag color="blue">{mcp.protocol}</Tag>
              {mcp.lifeStatus && <LifeStatusTag status={mcp.lifeStatus} />}
              <Tag>工具 {mcp.toolCount}</Tag>
            </div>
          </div>
        </div>
      </Card>

      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="MCP编码">
          <Text code>{mcp.mcpCode}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="协议类型">
          <Tag color="blue">{mcp.protocol}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="端点" span={2}>
          <Text copyable style={{ fontSize: 12 }}>{mcp.endpoint}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="安全等级">
          <SecurityLevelTag level={mcp.securityLevel} />
        </Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag color={mcp.status === 'ACTIVE' ? 'green' : 'default'}>
            {mcp.status === 'ACTIVE' ? '已接入' : '待接入'}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="版本">{mcp.version || '-'}</Descriptions.Item>
        <Descriptions.Item label="工具数">{mcp.toolCount}</Descriptions.Item>
        {mcp.provider && <Descriptions.Item label="提供方">{mcp.provider}</Descriptions.Item>}
        {mcp.authType && <Descriptions.Item label="鉴权类型">{mcp.authType}</Descriptions.Item>}
        {mcp.subsCount != null && <Descriptions.Item label="订阅数">{mcp.subsCount}</Descriptions.Item>}
        <Descriptions.Item label="发布时间">{mcp.publishedTime || '-'}</Descriptions.Item>
        {mcp.description && <Descriptions.Item label="描述" span={2}>{mcp.description}</Descriptions.Item>}
      </Descriptions>

      {basicInfo?.changeSummary && (
        <Alert type="info" showIcon style={{ marginTop: 12 }} message="变更说明" description={basicInfo.changeSummary} />
      )}
    </div>
  );

  /** 渲染工具详情 */
  const renderToolDetail = (tool: Tool) => (
    <div>
      <Card style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Avatar size={48} style={{ background: '#fef3c7', color: '#d97706' }}>
            <ToolOutlined style={{ fontSize: 24 }} />
          </Avatar>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 4 }}>
              {tool.toolName}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <Tag color={tool.toolType === 'BUILTIN' ? 'green' : tool.toolType === 'MCP_BOUND' ? 'blue' : 'orange'}>
                {tool.toolType === 'BUILTIN' ? '内置' : tool.toolType === 'CUSTOM' ? '自定义' : tool.toolType === 'MCP_BOUND' ? 'MCP绑定' : 'SKILL绑定'}
              </Tag>
              <Tag color={tool.sourceType === 'SYSTEM' ? 'default' : 'processing'}>
                {tool.sourceType === 'SYSTEM' ? '系统' : tool.sourceType === 'USER' ? '用户' : tool.sourceType === 'MCP' ? 'MCP' : 'SKILL'}
              </Tag>
              {tool.lifeStatus && <LifeStatusTag status={tool.lifeStatus} />}
              <Tag color={tool.status === 'ACTIVE' ? 'green' : tool.status === 'INACTIVE' ? 'default' : 'red'}>
                {tool.status === 'ACTIVE' ? '活跃' : tool.status === 'INACTIVE' ? '停用' : '已弃用'}
              </Tag>
            </div>
          </div>
        </div>
      </Card>

      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="工具编码">
          <Text code>{tool.toolCode}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="安全等级">
          <SecurityLevelTag level={tool.securityLevel} />
        </Descriptions.Item>
        <Descriptions.Item label="需审批">
          <Tag color={tool.requireApproval ? 'orange' : 'green'}>
            {tool.requireApproval ? '是' : '否'}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="创建时间">{tool.createdAt || '-'}</Descriptions.Item>
        {tool.sourceRef && <Descriptions.Item label="来源引用">{tool.sourceRef}</Descriptions.Item>}
        {tool.signature && <Descriptions.Item label="函数签名">{tool.signature}</Descriptions.Item>}
        {tool.description && <Descriptions.Item label="描述" span={2}>{tool.description}</Descriptions.Item>}
      </Descriptions>

      {tool.inputSchema && (
        <Card size="small" title="输入Schema" style={{ marginTop: 12 }}>
          <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, maxHeight: 200, overflow: 'auto', fontSize: 12, margin: 0 }}>
            {tool.inputSchema}
          </pre>
        </Card>
      )}

      {tool.outputSchema && (
        <Card size="small" title="输出Schema" style={{ marginTop: 12 }}>
          <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, maxHeight: 200, overflow: 'auto', fontSize: 12, margin: 0 }}>
            {tool.outputSchema}
          </pre>
        </Card>
      )}

      {basicInfo?.changeSummary && (
        <Alert type="info" showIcon style={{ marginTop: 12 }} message="变更说明" description={basicInfo.changeSummary} />
      )}
    </div>
  );

  /** 根据资源类型渲染对应详情 */
  const renderContent = () => {
    if (loading) {
      return <Spin style={{ display: 'block', padding: '40px 0', textAlign: 'center' }} />;
    }

    if (resourceType === 'AGENT' && agentDetail) {
      return renderAgentDetail(agentDetail);
    }
    if (resourceType === 'SKILL' && skillDetail) {
      return renderSkillDetail(skillDetail);
    }
    if (resourceType === 'KNOWLEDGE_BASE' && kbDetail) {
      return renderKbDetail(kbDetail);
    }
    if ((resourceType === 'MCP' || resourceType === 'MCP_SERVICE') && mcpDetail) {
      return renderMcpDetail(mcpDetail);
    }
    if (resourceType === 'TOOL' && toolDetail) {
      return renderToolDetail(toolDetail);
    }

    // 如果有基础信息但详情加载失败，显示基础信息
    if (basicInfo) {
      return (
        <Card>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="资源名称">
              <Text strong>{basicInfo.resourceName || '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="版本">{basicInfo.version || '-'}</Descriptions.Item>
            <Descriptions.Item label="安全等级">
              {basicInfo.securityLevel ? `L${basicInfo.securityLevel}` : '-'}
            </Descriptions.Item>
          </Descriptions>
          <Divider />
          <Text type="secondary" style={{ fontSize: 12 }}>
            详情加载失败，以下为审核单基础信息
          </Text>
        </Card>
      );
    }

    return <Empty description="暂无详情数据" />;
  };

  return (
    <div>
      {renderContent()}
    </div>
  );
};

export default ResourceReadOnlyDetail;

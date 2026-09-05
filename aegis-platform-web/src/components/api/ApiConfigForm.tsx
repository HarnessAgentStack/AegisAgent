/**
 * @file API 发布配置表单
 * @description 嵌入系统智能体创建/编辑页，仅在 agentType=SYSTEM 时显示。
 *              本组件**不再创建独立 Form 实例**，而是通过 props 接收外层 Form 实例，
 *              保证 useWatch / setFieldValue / Form.Item 全部挂到同一表单上下文，
 *              彻底消除 Bug3 "嵌套 Form 验证不同步" 问题。
 *
 *              包含：基础配置、入参 Schema、出参 Schema、鉴权配置、Bearer 详细配置、流量控制。
 * @author aegis
 * @since 2.0.0
 */
import React, { useEffect } from 'react';
import type { FormInstance } from 'antd';
import {
  Alert,
  Card,
  Checkbox,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Radio,
  Row,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { InfoCircleOutlined, KeyOutlined } from '@ant-design/icons';
import SchemaEditor from './SchemaEditor';
import {
  ApiAuthType,
  ApiResponseMode,
  BearerTokenMode,
  JwtAlgorithm,
} from '@/types/agentApi';

const { Text } = Typography;

interface ApiConfigFormProps {
  /** 外层 Form 实例，必须传入，以保证字段注册 / 监听 / 联动全部挂到同一表单 */
  form: FormInstance;
  /** 智能体编码（展示用 API 路径前缀） */
  agentCode?: string;
  /** 智能体名称（用于自动生成 apiName 默认值） */
  agentName?: string;
  /** 是否为编辑态（编辑态不覆盖已有 apiName） */
  isEdit?: boolean;
}

const ApiConfigForm: React.FC<ApiConfigFormProps> = ({ form, agentCode, agentName, isEdit }) => {
  const apiPath = agentCode ? `/api/v1/agent/${agentCode}/invoke` : '/api/v1/agent/{code}/invoke';

  // 关键修复：useWatch / setFieldValue 全部操作外层 Form 实例
  const authType = Form.useWatch(['apiConfig', 'authType'], form);
  const bearerMode = Form.useWatch(['apiConfig', 'bearerTokenMode'], form);
  const apiName = Form.useWatch(['apiConfig', 'apiName'], form);
  const apiNameManuallyEdited = React.useRef(false);

  // Bug3+4 修复：自动填充有意义的 apiName 默认值
  // - 编辑态不覆盖已有值
  // - 用户手动编辑后不再自动覆盖
  useEffect(() => {
    if (isEdit) return;
    if (apiNameManuallyEdited.current) return;
    const existing = form.getFieldValue(['apiConfig', 'apiName']);
    if (existing && existing.trim()) return;
    if (!agentName) return;
    const generated = `${agentName.trim()} API`;
    form.setFieldValue(['apiConfig', 'apiName'], generated);
  }, [agentName, form, isEdit, apiName]);

  // Bug3 修复：当 authType 切换为 BEARER 时，自动补默认的 bearerTokenMode / bearerJwtAlgorithm
  useEffect(() => {
    if (authType === ApiAuthType.BEARER) {
      if (!form.getFieldValue(['apiConfig', 'bearerTokenMode'])) {
        form.setFieldValue(['apiConfig', 'bearerTokenMode'], BearerTokenMode.PASSTHROUGH);
      }
      if (!form.getFieldValue(['apiConfig', 'bearerJwtAlgorithm'])) {
        form.setFieldValue(['apiConfig', 'bearerJwtAlgorithm'], JwtAlgorithm.HS256);
      }
    }
  }, [authType, form]);

  const isBearer = authType === ApiAuthType.BEARER;
  const isStaticBearer = bearerMode === BearerTokenMode.STATIC;
  const isPassThroughBearer = bearerMode === BearerTokenMode.PASSTHROUGH;

  const renderAuthNotice = () => {
    if (authType === ApiAuthType.API_KEY) {
      return (
        <Alert
          message="🔑 API Key 将在审核通过后自动生成并展示"
          description="当前仅配置鉴权方式，实际 Key 在审核通过后由系统自动生成，仅展示一次明文。"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
      );
    }
    if (authType === ApiAuthType.BEARER) {
      return (
        <Alert
          message={<Space><KeyOutlined /> Bearer Token 鉴权配置</Space>}
          description={'Bearer Token 通常由外部系统（OAuth2 Provider）动态颁发，有过期时间。推荐使用"透传验证"模式，由 Aegis 校验 Token 合法性后透传给下游智能体。'}
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />
      );
    }
    return null;
  };

  const jwtAlgorithm =
    form.getFieldValue(['apiConfig', 'bearerJwtAlgorithm']) || JwtAlgorithm.HS256;
  const isRsaOrEc = jwtAlgorithm.startsWith('RS') || jwtAlgorithm.startsWith('ES');

  return (
    <Card
      title="🚀 API 发布配置"
      size="small"
      style={{ marginTop: 16 }}
      extra={<Tag color="blue">仅系统智能体</Tag>}
    >
      {/* 基础配置 */}
      <Divider orientation="left" plain>
        基础配置
      </Divider>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name={['apiConfig', 'apiName']}
            label="API 名称"
            rules={[{ required: true, message: '请输入 API 名称' }]}
          >
            <Input
              placeholder="如 客服智能体服务"
              onChange={() => {
                apiNameManuallyEdited.current = true;
              }}
            />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name={['apiConfig', 'httpMethod']} label="HTTP 方法" initialValue="POST">
            <Select
              options={[
                { value: 'POST', label: 'POST' },
                { value: 'GET', label: 'GET' },
              ]}
            />
          </Form.Item>
        </Col>
        <Col span={24}>
          <Form.Item name={['apiConfig', 'apiPath']} label="API 路径" extra="系统自动生成，审核通过后生效">
            <Input value={apiPath} disabled />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name={['apiConfig', 'responseMode']} label="响应模式" initialValue={ApiResponseMode.SYNC}>
            <Radio.Group>
              <Radio value={ApiResponseMode.SYNC}>同步</Radio>
              <Radio value={ApiResponseMode.ASYNC}>异步</Radio>
              <Radio value={ApiResponseMode.SSE}>SSE 流式</Radio>
            </Radio.Group>
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name={['apiConfig', 'timeout']} label="超时时间(秒)" initialValue={60}>
            <InputNumber min={5} max={300} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      {/* 入参 Schema */}
      <Divider orientation="left" plain>
        入参 Schema
      </Divider>
      <Form.Item name={['apiConfig', 'requestSchema']}>
        <SchemaEditor />
      </Form.Item>

      {/* 出参 Schema */}
      <Divider orientation="left" plain>
        出参 Schema
      </Divider>
      <Form.Item name={['apiConfig', 'responseSchema']}>
        <SchemaEditor />
      </Form.Item>

      {/* 鉴权配置 */}
      <Divider orientation="left" plain>
        鉴权配置
      </Divider>
      <Form.Item name={['apiConfig', 'authType']} label="鉴权方式" initialValue={ApiAuthType.API_KEY}>
        <Radio.Group>
          <Radio value={ApiAuthType.API_KEY}>API Key</Radio>
          <Radio value={ApiAuthType.BEARER}>Bearer</Radio>
          <Radio value={ApiAuthType.OAUTH2}>OAuth2</Radio>
          <Radio value={ApiAuthType.BASIC}>Basic</Radio>
          <Radio value={ApiAuthType.NONE}>无认证</Radio>
        </Radio.Group>
      </Form.Item>

      {renderAuthNotice()}

      {/* Bearer Token 专属配置 */}
      {isBearer && (
        <>
          <Divider orientation="left" plain>
            <Space>
              <KeyOutlined />
              Bearer Token 详细配置
            </Space>
          </Divider>

          <Row gutter={16}>
            <Col span={24}>
              <Form.Item
                name={['apiConfig', 'bearerTokenMode']}
                label="Token 管理模式"
                initialValue={BearerTokenMode.PASSTHROUGH}
                rules={[{ required: true, message: '请选择 Token 管理模式' }]}
              >
                <Radio.Group>
                  <Radio value={BearerTokenMode.PASSTHROUGH}>
                    <Space>
                      <span>透传验证（推荐）</span>
                      <Tag color="green">外部动态Token</Tag>
                    </Space>
                  </Radio>
                  <Radio value={BearerTokenMode.STATIC}>
                    <Space>
                      <span>静态配置</span>
                      <Tag color="orange">Token不过期</Tag>
                    </Space>
                  </Radio>
                </Radio.Group>
              </Form.Item>
            </Col>
          </Row>

          {/* 静态模式配置 */}
          {isStaticBearer && (
            <Row gutter={16}>
              <Col span={24}>
                <Form.Item
                  name={['apiConfig', 'bearerTokenValue']}
                  label={
                    <Space>
                      <span>Token 值</span>
                      <Tooltip title="调用方需在 Authorization Header 中携带此 Token">
                        <InfoCircleOutlined style={{ color: '#faad14' }} />
                      </Tooltip>
                    </Space>
                  }
                  rules={[{ required: true, message: '请输入 Bearer Token 值' }]}
                  extra="调用方需通过 Authorization: Bearer {token} 头部传入，Aegis 做精确比对。"
                >
                  <Input.Password
                    placeholder="请输入固定 Token 值"
                    autoComplete="new-password"
                  />
                </Form.Item>
              </Col>
            </Row>
          )}

          {/* 透传模式配置 */}
          {isPassThroughBearer && (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Row gutter={16}>
                <Col span={14}>
                  <Form.Item
                    name={['apiConfig', 'bearerJwtAlgorithm']}
                    label="签名算法"
                    initialValue={JwtAlgorithm.HS256}
                    rules={[{ required: true, message: '请选择签名算法' }]}
                  >
                    <Select
                      showSearch
                      options={[
                        {
                          label: '对称签名 (HMAC)',
                          options: [
                            { value: JwtAlgorithm.HS256, label: 'HS256（推荐）' },
                            { value: JwtAlgorithm.HS384, label: 'HS384' },
                            { value: JwtAlgorithm.HS512, label: 'HS512' },
                          ],
                        },
                        {
                          label: '非对称签名 (RSA)',
                          options: [
                            { value: JwtAlgorithm.RS256, label: 'RS256' },
                            { value: JwtAlgorithm.RS384, label: 'RS384' },
                            { value: JwtAlgorithm.RS512, label: 'RS512' },
                          ],
                        },
                        {
                          label: '非对称签名 (EC)',
                          options: [
                            { value: JwtAlgorithm.ES256, label: 'ES256' },
                          ],
                        },
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col span={10}>
                  <Form.Item
                    name={['apiConfig', 'bearerPassThrough']}
                    label="Token 透传"
                    valuePropName="checked"
                    extra="将 Authorization Header 透传给下游 Agent 服务"
                  >
                    <Checkbox>
                      <Space>
                        启用到下游
                        <Tooltip title="透传后，Agent 后端服务可读取原始 Bearer Token 做二次鉴权">
                          <InfoCircleOutlined style={{ color: '#8c8c8c' }} />
                        </Tooltip>
                      </Space>
                    </Checkbox>
                  </Form.Item>
                </Col>
              </Row>

              <Row gutter={16}>
                <Col span={24}>
                  <Form.Item
                    name={['apiConfig', 'bearerJwtSecret']}
                    label={
                      <Space>
                        <span>签名密钥 / 公钥</span>
                        <Tag color="orange">
                          {isRsaOrEc ? 'Base64 编码公钥' : 'HMAC 密钥'}
                        </Tag>
                      </Space>
                    }
                    rules={[{ required: true, message: '请输入签名密钥' }]}
                    extra={
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {isRsaOrEc
                          ? '粘贴 Base64 编码的 RSA/EC 公钥，Aegis 使用公钥验证 JWT 签名合法性'
                          : 'HMAC 对称密钥，用于验证 JWT 签名。建议使用至少 32 字节的随机字符串'}
                      </Text>
                    }
                  >
                    <Input.TextArea
                      rows={3}
                      placeholder={
                        isRsaOrEc
                          ? 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQ...'
                          : '请输入 HMAC 密钥（至少 32 字节）'
                      }
                    />
                  </Form.Item>
                </Col>
              </Row>

              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 0 }}
                message="💡 透传验证流程"
                description={
                  <Space direction="vertical" size={2}>
                    <Text style={{ fontSize: 12 }}>
                      1. 调用方通过 <Text code>Authorization: Bearer {"<token>"}</Text> Header 传入 JWT
                    </Text>
                    <Text style={{ fontSize: 12 }}>
                      2. Aegis 使用配置的密钥验证签名 + 检查过期时间
                    </Text>
                    <Text style={{ fontSize: 12 }}>
                      3. 验证通过 → 继续执行；过期/签名失败 → 返回 401
                    </Text>
                    <Text style={{ fontSize: 12 }}>
                      4. 若启用了"Token 透传"，Authorization Header 将转发给 Agent 后端
                    </Text>
                  </Space>
                }
              />
            </Space>
          )}
        </>
      )}

      {/* 流量控制 */}
      <Divider orientation="left" plain>
        流量控制
      </Divider>
      <Row gutter={16}>
        <Col span={8}>
          <Form.Item name={['apiConfig', 'rateLimit']} label="QPS 限流(次/秒)" initialValue={100}>
            <InputNumber min={1} max={10000} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item name={['apiConfig', 'concurrentLimit']} label="并发上限" initialValue={10}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Text type="secondary" style={{ fontSize: 12 }}>
        📌 API 将在审核通过后自动开通，智能体被禁用时 API 同步停止服务。
      </Text>
    </Card>
  );
};

export default ApiConfigForm;

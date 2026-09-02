-- =============================================================================
-- Aegis Platform - 种子数据 (A 类：平台种子/预置配置数据最小集)
-- -----------------------------------------------------------------------------
-- 用途：新部署最小可运行数据集，保证「建库 → 起服务 → 可登录 → 有沙箱
--       → 有安全基线 → 能对话」闭环。
-- 执行：MySQL 容器首次启动时由 /docker-entrypoint-initdb.d 自动执行
--       （依赖 01_schema_init.sql 先建表）；也可手动 mysql < 本文件。
-- 幂等：所有 INSERT 均为 INSERT IGNORE，重复执行不报错。
-- 安全：
--   1. 仅含 admin 初始账号，密码明文 aegis@123 (BCrypt cost=10)，
--      生产环境首次登录后请立即修改密码。
--   2. 已剔除所有业务运行数据（会话/审计/知识库内容/沙箱实例等）。
--   3. model_provider / model_def / model_route 不含本文件——
--      模型配置含 API Key，需部署后通过管理页面配置（见部署文档）。
-- 自动注入说明（无需本文件，由代码启动注入）：
--   - universal 通用智能体     : TenantBootstrapService (aegis-admin ApplicationRunner)
--   - skill_creator 元技能     : SkillCreatorInitializer (aegis-runtime CommandLineRunner)
--   - 建租户联动 7 角色        : TenantManageService.create() API
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. 租户 ten_tenant：默认租户 DEFAULT
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `ten_tenant`
  (`id`, `tenant_code`, `tenant_name`, `tenant_type`, `status`, `contact_name`, `contact_phone`, `expire_time`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (1, 'DEFAULT', '默认租户', 'HQ', 'NORMAL', '系统管理员', '13800000000', NULL, NULL, 1, NOW(), NULL, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 2. 组织 org_department / org_role / org_user / org_user_role
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_department`
  (`id`, `tenant_id`, `dept_name`, `parent_id`, `dept_path`, `dept_level`, `sort`, `leader_user_id`, `status`, `sync_source`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (1, 1, '集团总部', 0, '/1', 1, 1, NULL, 'NORMAL', 'MANUAL', 1, NOW(), NULL, NOW(), 0);

INSERT IGNORE INTO `org_role`
  (`id`, `tenant_id`, `role_code`, `role_name`, `role_type`, `parent_role_id`, `description`, `sort`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (1, 1, 'SUPER_ADMIN',     '超级管理员',     'PLATFORM', NULL, '全平台所有权限',                       1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2, 1, 'ENTERPRISE_ADMIN','企业管理员',     'PLATFORM', NULL, '组织/权限/安全/监管/沙箱/预算',         2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (3, 1, 'SECURITY_ADMIN',  '安全管理员',     'PLATFORM', NULL, '安全策略/审计日志/运行时监管/合规报表', 3, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (4, 1, 'RESOURCE_ADMIN',  '资源管理员',     'PLATFORM', NULL, 'SKILL/MCP/知识库/工具审核与发布',       4, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (5, 1, 'AGENT_REVIEWER',  '智能体审核员',   'PLATFORM', NULL, '智能体发布审核/订阅审批/API审核',       5, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (6, 1, 'AGENT_CREATOR',   '智能体创建者',   'PLATFORM', NULL, '创建智能体/绑定资源/提交审核/发布API',  6, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (7, 1, 'EMPLOYEE',        '普通员工',       'PLATFORM', NULL, '使用通用智能体/订阅应用智能体/管理个人会话', 7, 'NORMAL', 1, NOW(), NULL, NOW(), 0);

-- 初始管理员 admin / 密码：aegis@123 （BCrypt cost=10，首次登录请修改）
INSERT IGNORE INTO `org_user`
  (`id`, `tenant_id`, `username`, `password`, `real_name`, `emp_no`, `email`, `phone`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `dept_id`, `mfa_enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (1, 1, 'admin', '$2a$10$HUp0dT0GPeivBOtBMdooCOdx4cVaAkTrIpI.vp3X8MbWrhNIiuRru', '系统管理员', 'EMP000001', 'admin@aegis.com', '13800000000', NULL, 'NORMAL', NULL, NULL, NULL, 0, 1, NOW(), NULL, NOW(), 0);

INSERT IGNORE INTO `org_user_role`
  (`id`, `tenant_id`, `user_id`, `role_id`, `resource_id`, `resource_type`, `source`, `expire_time`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (1, 1, 1, 1, NULL, NULL, 'DIRECT', NULL, 1, NOW(), NULL, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 3. 租户配额 ten_quota：DEFAULT 租户默认配额档
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `ten_quota`
  (`id`, `tenant_id`, `max_agents`, `max_resources`, `max_concurrent_sessions`, `max_token_per_day`, `max_token_per_month`, `max_sandboxes`, `max_storage_gb`, `create_by`, `create_time`, `update_time`, `deleted`)
VALUES
  (1, 1, 1000, 10000, 500, 10000000, 300000000, 200, 1000, 1, NOW(), NOW(), 0);

-- -----------------------------------------------------------------------------
-- 4. 沙箱镜像 sbx_base_image：Docker Hub 公开镜像
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sbx_base_image`
  (`id`, `tenant_id`, `image_code`, `image_name`, `description`, `registry_type`, `registry`, `repository`, `tag`, `digest`, `image_size_mb`, `status`, `create_by`, `create_time`, `update_time`, `deleted`)
VALUES
  (1, 0, 'python311-slim', 'Python 3.11 Slim 沙箱', '轻量级 Python 3.11 沙箱镜像，内置 pip + 常用科学计算库，适合代码执行/数据分析场景', 'DOCKER_HUB', 'docker.io', 'library/python', '3.11-slim', 'sha256:d1dd85f317b225394a85e7822923c4e9f3183b13a2fc05c172a940b937d1483a', 189, 'ENABLED', 1, NOW(), NOW(), 0),
  (2, 0, 'python39-slim',  'Python 3.9 Slim 沙箱',  '兼容 Python 3.9 旧版本的沙箱镜像，适合运行历史项目代码', 'DOCKER_HUB', 'docker.io', 'library/python', '3.9-slim', NULL, 185, 'ENABLED', 1, NOW(), NOW(), 0);

-- -----------------------------------------------------------------------------
-- 5. 沙箱池 sbx_pool：默认 STANDARD 池（关联镜像 id=1）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sbx_pool`
  (`id`, `tenant_id`, `pool_code`, `namespace`, `min_instances`, `max_instances`, `idle_timeout_min`, `base_image_id`, `pool_name`, `pool_type`, `applicable_scene`, `network_policy`, `cpu_limit`, `mem_limit_mb`, `disk_limit_gb`, `status`, `last_reconcile_time`, `create_by`, `create_time`, `update_time`, `deleted`)
VALUES
  (1, 1, 'STANDARD', 'aegis-sbx-t1-standard', 1, 5, 30, 1, '标准执行池', 'STANDARD', '通用代码执行与文件处理场景', 'RESTRICTED', '1', 256, 5, 'ENABLED', NULL, NULL, NOW(), NOW(), 0);

-- -----------------------------------------------------------------------------
-- 6. 内置工具 res_tool：16 个 BUILTIN 工具
--    Bridge 分派: aegis_execute → AegisExecuteTool (K8s沙箱Python执行)
--                 generate_file  → AegisGenerateFileTool
--                 http_request   → AegisHttpTool (SSRF防护) / network_request为别名
--                 web_search, image_search, memory_search, session_search,
--                 search_history, image_generation → AegisBuiltinTools (@Tool注解)
--    data_query/chart_gen/report_write/file_read/file_write/file_list 为保留接口（待接入）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `res_tool`
  (`id`, `tool_code`, `tool_name`, `description`, `tool_type`, `source_type`, `mcp_service_id`, `read_only`, `input_schema`, `output_schema`, `security_level`, `status`, `create_by`, `create_time`, `update_time`, `deleted`)
VALUES
  (3001, 'data_query',    '数据查询工具',  '查询业务数据库返回结构化结果',                                    'READONLY',        'BUILTIN', NULL, 1, '{"type": "object", "required": ["table"], "properties": {"sql": {"type": "string"}, "table": {"type": "string"}}}',                                                                                                                                      '{"type": "object", "properties": {"rows": {"type": "array"}}}',                                                                                                                'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3002, 'chart_gen',     '图表生成工具',  '根据数据生成可视化图表',                                          'INTERNAL_API',    'BUILTIN', NULL, 1, '{"type": "object", "required": ["data", "type"], "properties": {"data": {"type": "array"}, "type": {"type": "string"}}}',                                                                                                                              '{"type": "object", "properties": {"chartUrl": {"type": "string"}}}',                                                                                                           'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3003, 'report_write',  '报告撰写工具',  '基于数据与图表生成分析报告',                                      'INTERNAL_API',    'BUILTIN', NULL, 0, '{"type": "object", "required": ["data"], "properties": {"data": {"type": "object"}, "chart": {"type": "string"}}}',                                                                                                                                    '{"type": "object", "properties": {"report": {"type": "string"}}}',                                                                                                             'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3101, 'file_read',     '文件读取',      '读取本地文件或已上传附件的内容。支持通过路径或fileId读取文本、Markdown、JSON等文件。',   'READONLY',        'BUILTIN', NULL, 1, '{"type": "object", "properties": {"path": {"type": "string", "description": "本地文件路径，如 /workspace/user_files/resume.docx"}, "fileId": {"type": "string", "description": "已上传附件的fileId"}}}',                                                                          '{"type": "object", "properties": {"fileId": {"type": "string"}, "content": {"type": "string"}}}',                                                                              'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3102, 'file_write',    '文件写入',      '将内容写入文件并返回下载链接。支持生成docx、md、txt、json等格式的文件。',               'WRITE',           'BUILTIN', NULL, 0, '{"type": "object", "required": ["filename", "content"], "properties": {"content": {"type": "string", "description": "文件内容"}, "filename": {"type": "string", "description": "文件名，如 optimized_resume.md"}}}',                                                                '{"type": "object", "properties": {"fileId": {"type": "string"}, "filename": {"type": "string"}, "downloadUrl": {"type": "string"}}}',                                            'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3103, 'file_list',     '文件列表',      '列出指定目录下的文件列表，默认列出用户文件目录。',                                  'READONLY',        'BUILTIN', NULL, 1, '{"type": "object", "properties": {"dir": {"type": "string", "description": "目录路径，可选，默认为用户文件目录"}}}',                                                                                                                                  '{"type": "object", "properties": {"files": {"type": "array", "items": {"type": "object", "properties": {"name": {"type": "string"}, "size": {"type": "number"}}}}}}',           'L1', 'NORMAL', 1, NOW(), NOW(), 0),
  (3104, 'generate_file', '生成文件',      '生成文件并返回下载链接。可指定文件名、内容和MIME类型，适用于生成简历、报告等文档。',   'WRITE',           'BUILTIN', NULL, 0, '{"type": "object", "required": ["filename", "content"], "properties": {"content": {"type": "string", "description": "文件内容"}, "filename": {"type": "string", "description": "文件名，如 optimized_resume.docx"}, "contentType": {"type": "string", "description": "MIME类型，如 text/markdown、application/vnd.openxmlformats-officedocument.wordprocessingml.document"}}}', '{"type": "object", "properties": {"size": {"type": "number"}, "fileId": {"type": "string"}, "filename": {"type": "string"}, "downloadUrl": {"type": "string"}}}',                  'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3105, 'web_search',    '联网搜索',      '联网搜索，返回搜索结果摘要。用于查询实时信息，如天气、新闻、知识等。',                'READONLY',        'BUILTIN', NULL, 1, '{"type": "object", "required": ["query"], "properties": {"query": {"type": "string", "description": "搜索关键词，如 天津天气预报"}}}',                                                                                                                  '{"type": "object", "properties": {"query": {"type": "string"}, "result": {"type": "string"}, "status": {"type": "number"}}}',                                                  'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3106, 'http_request',  'HTTP请求',      '发起 HTTP GET/POST 请求，获取指定 URL 的内容。内置 SSRF 防护，禁止访问内网地址。',    'READONLY',        'BUILTIN', NULL, 1, '{"type": "object", "required": ["url"], "properties": {"url": {"type": "string", "description": "请求 URL，如 https://api.example.com/data"}, "body": {"type": "string", "description": "POST 请求体"}, "method": {"type": "string", "description": "HTTP 方法：GET 或 POST，默认 GET"}, "headers": {"type": "object", "description": "自定义请求头"}}}', '{"type": "object", "properties": {"url": {"type": "string"}, "result": {"type": "string"}, "status": {"type": "number"}}}',                                                    'L3', 'NORMAL', 1, NOW(), NOW(), 0),

  (3107, 'aegis_execute',    '代码执行',    '在Aegis K8s沙箱环境中执行Python代码，支持数学计算、数据处理等编程任务。代码在隔离Pod中执行，不影响宿主系统。', 'CODE_EXEC', 'BUILTIN', NULL, 0, '{"type":"object","properties":{"code":{"type":"string","description":"要执行的Python代码片段"},"command":{"type":"string","description":"Python代码片段（兼容参数，等同于code）"},"language":{"type":"string","description":"编程语言，默认python"}},"anyOf":[{"required":["code"]},{"required":["command"]}]}', '{"type":"object","properties":{"result":{"type":"string"},"stdout":{"type":"string"},"stderr":{"type":"string"},"success":{"type":"boolean"}}}', 'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3108, 'image_search',     '图片搜索',    '联网搜索图片，返回图片URL、标题和缩略图。', 'READONLY', 'BUILTIN', NULL, 1, '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"搜索关键词"},"count":{"type":"integer","description":"返回数量，默认5"}}}', '{"type":"object","properties":{"images":{"type":"array"}}}', 'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3109, 'memory_search',    '记忆搜索',    '检索历史对话中的记忆内容。', 'READONLY', 'BUILTIN', NULL, 1, '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"搜索关键词"},"scope":{"type":"string","description":"搜索范围：session=当前会话，all=所有会话，默认all"}}}', '{"type":"object","properties":{"results":{"type":"array"}}}', 'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3110, 'session_search',   '会话搜索',    '在当前会话中查找之前讨论过的内容。', 'READONLY', 'BUILTIN', NULL, 1, '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"搜索关键词"}}}', '{"type":"object","properties":{"results":{"type":"array"}}}', 'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3111, 'search_history',   '历史搜索',    '检索历史搜索记录和对话记录。', 'READONLY', 'BUILTIN', NULL, 1, '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"搜索关键词"},"days":{"type":"integer","description":"最近几天，默认7"}}}', '{"type":"object","properties":{"history":{"type":"array"}}}', 'L2', 'NORMAL', 1, NOW(), NOW(), 0),
  (3112, 'image_generation', '图片生成',    'AI生成图片，支持多种风格和尺寸。', 'WRITE', 'BUILTIN', NULL, 0, '{"type":"object","required":["prompt"],"properties":{"prompt":{"type":"string","description":"图片描述"},"size":{"type":"string","description":"图片尺寸，如1024x1024"}}}', '{"type":"object","properties":{"imageUrl":{"type":"string"},"description":{"type":"string"}}}', 'L3', 'NORMAL', 1, NOW(), NOW(), 0),
  (3113, 'network_request',  '网络请求',    '发起HTTP GET/POST请求，获取指定URL内容。http_request别名。内置SSRF防护。', 'READONLY', 'BUILTIN', NULL, 1, '{"type":"object","required":["url"],"properties":{"url":{"type":"string","description":"请求URL"},"body":{"type":"string","description":"POST请求体"},"method":{"type":"string","description":"HTTP方法：GET或POST"},"headers":{"type":"object","description":"自定义请求头"}}}', '{"type":"object","properties":{"url":{"type":"string"},"result":{"type":"string"},"status":{"type":"number"}}}', 'L3', 'NORMAL', 1, NOW(), NOW(), 0);
-- -----------------------------------------------------------------------------
-- 7. 审批节点 res_review_node：PUBLIC 发布默认两级审批链
--    (RESOURCE_ADMIN → TENANT_ADMIN)，tenant_id=0 为平台级默认链
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `res_review_node`
  (`id`, `tenant_id`, `review_chain_id`, `node_order`, `approver_type`, `approver_ref`, `node_status`, `reviewed_by`, `reviewed_time`, `comment`, `create_time`, `deleted`)
VALUES
  (2000000000000000101, 0, 'default_public', 1, 'ROLE', 'RESOURCE_ADMIN', 'PENDING', NULL, NULL, NULL, NOW(), 0),
  (2000000000000000102, 0, 'default_public', 2, 'ROLE', 'TENANT_ADMIN',   'PENDING', NULL, NULL, NULL, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 8. 工具策略 sec_tool_policy：工具类型 × 安全等级 → 动作 完整矩阵（24 条）
--    设计原则（演示友好优先）：
--    - L1 公开级：全 ALLOW（最高演示友好，公开业务无阻碍）
--    - L2 内部级：全 ALLOW（内部业务正常操作，外网/代码执行也放行）
--    - L3 机密级：EXTERNAL_NETWORK/CODE_EXEC/HIGH_RISK 降为 APPROVE（还能审批放行）
--    - L4 绝密级：只有 READONLY 还能 APPROVE，其余全 REJECT（严格管控）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_tool_policy`
  (`id`, `tenant_id`, `tool_type`, `security_level`, `action`, `description`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  -- READONLY（只读查询）
  (11001, 1, 'READONLY',        1, 'ALLOW',   '只读工具 L1：公开级直接放行',      1, 1, NOW(), 1, NOW(), 0),
  (11002, 1, 'READONLY',        2, 'ALLOW',   '只读工具 L2：内部级直接放行',      1, 1, NOW(), 1, NOW(), 0),
  (11003, 1, 'READONLY',        3, 'ALLOW',   '只读工具 L3：机密级直接放行',      1, 1, NOW(), 1, NOW(), 0),
  (11004, 1, 'READONLY',        4, 'APPROVE', '只读工具 L4：绝密级需审批',        1, 1, NOW(), 1, NOW(), 0),

  -- INTERNAL_API（内部接口）
  (11005, 1, 'INTERNAL_API',    1, 'ALLOW',   '内部API L1：公开级直接放行',       1, 1, NOW(), 1, NOW(), 0),
  (11006, 1, 'INTERNAL_API',    2, 'ALLOW',   '内部API L2：内部级直接放行',       1, 1, NOW(), 1, NOW(), 0),
  (11007, 1, 'INTERNAL_API',    3, 'ALLOW',   '内部API L3：机密级直接放行',       1, 1, NOW(), 1, NOW(), 0),
  (11008, 1, 'INTERNAL_API',    4, 'REJECT',  '内部API L4：绝密级拒绝',           1, 1, NOW(), 1, NOW(), 0),

  -- WRITE（写入操作）
  (11009, 1, 'WRITE',           1, 'ALLOW',   '写入工具 L1：公开级直接放行',      1, 1, NOW(), 1, NOW(), 0),
  (11010, 1, 'WRITE',           2, 'ALLOW',   '写入工具 L2：内部级直接放行',      1, 1, NOW(), 1, NOW(), 0),
  (11011, 1, 'WRITE',           3, 'APPROVE', '写入工具 L3：机密级需审批',        1, 1, NOW(), 1, NOW(), 0),
  (11012, 1, 'WRITE',           4, 'REJECT',  '写入工具 L4：绝密级拒绝',          1, 1, NOW(), 1, NOW(), 0),

  -- EXTERNAL_NETWORK（外网访问）
  (11013, 1, 'EXTERNAL_NETWORK',1, 'ALLOW',   '外网工具 L1：公开级放行（白名单域名）',1,1,NOW(),1,NOW(),0),
  (11014, 1, 'EXTERNAL_NETWORK',2, 'ALLOW',   '外网工具 L2：内部级放行',          1, 1, NOW(), 1, NOW(), 0),
  (11015, 1, 'EXTERNAL_NETWORK',3, 'APPROVE', '外网工具 L3：机密级需审批',        1, 1, NOW(), 1, NOW(), 0),
  (11016, 1, 'EXTERNAL_NETWORK',4, 'REJECT',  '外网工具 L4：绝密级禁止出网',      1, 1, NOW(), 1, NOW(), 0),

  -- CODE_EXEC（代码执行）
  (11017, 1, 'CODE_EXEC',       1, 'ALLOW',   '代码执行 L1：公开级放行（沙箱隔离）',1,1,NOW(),1,NOW(),0),
  (11018, 1, 'CODE_EXEC',       2, 'ALLOW',   '代码执行 L2：内部级放行',          1, 1, NOW(), 1, NOW(), 0),
  (11019, 1, 'CODE_EXEC',       3, 'APPROVE', '代码执行 L3：机密级需审批',        1, 1, NOW(), 1, NOW(), 0),
  (11020, 1, 'CODE_EXEC',       4, 'REJECT',  '代码执行 L4：绝密级禁止',          1, 1, NOW(), 1, NOW(), 0),

  -- HIGH_RISK（高风险操作）
  (11021, 1, 'HIGH_RISK',       1, 'ALLOW',   '高危工具 L1：公开级放行（沙箱隔离）',1,1,NOW(),1,NOW(),0),
  (11022, 1, 'HIGH_RISK',       2, 'ALLOW',   '高危工具 L2：内部级放行',          1, 1, NOW(), 1, NOW(), 0),
  (11023, 1, 'HIGH_RISK',       3, 'APPROVE', '高危工具 L3：机密级需审批',        1, 1, NOW(), 1, NOW(), 0),
  (11024, 1, 'HIGH_RISK',       4, 'REJECT',  '高危工具 L4：绝密级禁止',          1, 1, NOW(), 1, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 9. 脱敏规则 sec_mask_rule：8 条生产基线
--    - 修 BANK_CARD regex：原 \d{16,19} 太宽会误伤订单号，改为银联卡前缀 62[0-9]{14,17}
--    - 新增 PASSPORT / LICENSE / COMPANY_ID 覆盖常见证件类型
--    - IP 脱敏改为 ALL（整段替换为 ***.*.*），避免泄露网段信息
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_mask_rule`
  (`id`, `tenant_id`, `data_type`, `regex`, `mask_way`, `example`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (13001, 1, 'PHONE',      '1[3-9]\\d{9}',                                                                                          'MIDDLE4',       '138****5678',          1, 1, NOW(), 1, NOW(), 0),
  (13002, 1, 'ID_CARD',    '[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]',              'KEEP_HEAD_TAIL','110***********1234',   1, 1, NOW(), 1, NOW(), 0),
  (13003, 1, 'BANK_CARD',  '62[0-9]{14,17}',                                                                                        'KEEP_LAST4',    '************1234',     1, 1, NOW(), 1, NOW(), 0),
  (13004, 1, 'EMAIL',      '[\\w.+-]+@[\\w-]+\\.[\\w.-]+',                                                                         'KEEP_HEAD_TAIL','z***@example.com',     1, 1, NOW(), 1, NOW(), 0),
  (13005, 1, 'IP',         '\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}',                                                             'ALL',           '***.***.***.***',     1, 1, NOW(), 1, NOW(), 0),
  (13006, 1, 'PASSPORT',   '[EeGgPp][0-9]{8}',                                                                                      'KEEP_HEAD_TAIL','E******67',            1, 1, NOW(), 1, NOW(), 0),
  (13007, 1, 'LICENSE',    '[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5}',                       'KEEP_LAST4',    '京A12345 → 京****5',   1, 1, NOW(), 1, NOW(), 0),
  (13008, 1, 'COMPANY_ID', '[0-9A-HJ-NPQRTUWXY]{18}',                                                                               'KEEP_HEAD_TAIL','911100***********9X', 1, 1, NOW(), 1, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 10. 出站策略 sec_outbound_policy：SSRF 防护 + 示例白名单（12 条）
--     设计原则：
--     - BLACKLIST_IP 覆盖全部内网网段（SSRF 防护基线）：回环、A/B/C 类内网、链路本地、
--       IPv4/IPv6 私网、云元数据段 169.254.0.0/16
--     - WHITELIST_DOMAIN 给 6 条常见云服务示例（生产需按实际业务调整）
--     - 所有域名默认限制 443 端口（HTTPS），禁止 80/8080/8443 等非标准端口
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_outbound_policy`
  (`id`, `tenant_id`, `policy_type`, `domain`, `ip_cidr`, `port_limit`, `applicable_scope`, `scope_config`, `valid_hours`, `description`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  -- ========== BLACKLIST_IP：SSRF 防护基线（全部内网网段 + 链路本地） ==========
  (17001, 1, 'BLACKLIST_IP', NULL, '127.0.0.0/8',    NULL, 'ALL', NULL, NULL, '禁止访问回环地址（SSRF 防护）',                     1, 1, NOW(), 1, NOW(), 0),
  (17002, 1, 'BLACKLIST_IP', NULL, '10.0.0.0/8',     NULL, 'ALL', NULL, NULL, '禁止访问 A 类内网 10.0.0.0/8（SSRF 防护）',          1, 1, NOW(), 1, NOW(), 0),
  (17003, 1, 'BLACKLIST_IP', NULL, '172.16.0.0/12',  NULL, 'ALL', NULL, NULL, '禁止访问 B 类内网 172.16.0.0/12（SSRF 防护）',       1, 1, NOW(), 1, NOW(), 0),
  (17004, 1, 'BLACKLIST_IP', NULL, '192.168.0.0/16', NULL, 'ALL', NULL, NULL, '禁止访问 C 类内网 192.168.0.0/16（SSRF 防护）',      1, 1, NOW(), 1, NOW(), 0),
  (17005, 1, 'BLACKLIST_IP', NULL, '169.254.0.0/16', NULL, 'ALL', NULL, NULL, '禁止访问链路本地 169.254.0.0/16（含云元数据服务）',    1, 1, NOW(), 1, NOW(), 0),
  (17006, 1, 'BLACKLIST_IP', NULL, '0.0.0.0/8',      NULL, 'ALL', NULL, NULL, '禁止访问当前网络 0.0.0.0/8（SSRF 防护）',           1, 1, NOW(), 1, NOW(), 0),

  -- ========== WHITELIST_DOMAIN：示例（生产请按实际业务调整） ==========
  (17010, 1, 'WHITELIST_DOMAIN', 'api.openai.com',        NULL, 443, 'ALL',  NULL, NULL, '允许调用 OpenAI API（HTTPS 443）',                  1, 1, NOW(), 1, NOW(), 0),
  (17011, 1, 'WHITELIST_DOMAIN', '*.volces.com',          NULL, 443, 'ALL',  NULL, NULL, '允许调用火山引擎（豆包）API（HTTPS 443）',          1, 1, NOW(), 1, NOW(), 0),
  (17012, 1, 'WHITELIST_DOMAIN', '*.aliyuncs.com',        NULL, 443, 'ALL',  NULL, NULL, '允许访问阿里云 OSS（HTTPS 443）',                  1, 1, NOW(), 1, NOW(), 0),
  (17013, 1, 'WHITELIST_DOMAIN', '*.tencentcloudapi.com', NULL, 443, 'ALL',  NULL, NULL, '允许调用腾讯云 API（HTTPS 443）',                  1, 1, NOW(), 1, NOW(), 0),
  (17014, 1, 'WHITELIST_DOMAIN', '*.baidu.com',           NULL, 443, 'ALL',  NULL, NULL, '允许访问百度系 API（HTTPS 443）',                  1, 1, NOW(), 1, NOW(), 0),
  (17015, 1, 'WHITELIST_DOMAIN', 'weixin.qq.com',         NULL, 443, 'ALL',  NULL, NULL, '允许访问微信开放平台（HTTPS 443）',                1, 1, NOW(), 1, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 11. 敏感词 sec_sensitive_word：9 条生产基线（5 BLOCK + 4 REPLACE）
--     设计说明：
--     - BLOCK 词（5 条）：覆盖诈骗/赌博/毒品/自杀/色情五类高频违禁，词库为示例框架，
--       生产应按行业合规要求动态扩充（政治敏感/暴力恐怖等具体词不硬编码在 seed 中）
--     - REPLACE 词（4 条）：对隐私关键词脱敏替换，避免 token/密钥等在对话/输出中明文泄露
--     - 结构化数据（手机号/身份证/银行卡/邮箱）正则脱敏由 sec_mask_rule 负责，
--       敏感词表仅处理字面匹配的隐私关键词
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_sensitive_word`
  (`id`, `tenant_id`, `word`, `category`, `match_mode`, `action`, `replace_text`, `scope`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  -- ========== BLOCK 拦截类（通用违禁） ==========
  (12001, 1, '诈骗',   'GENERAL', 'EXACT', 'BLOCK', NULL,   'INPUT',       1, 1, NOW(), 1, NOW(), 0),
  (12002, 1, '赌博',   'GENERAL', 'EXACT', 'BLOCK', NULL,   'INPUT',       1, 1, NOW(), 1, NOW(), 0),
  (12003, 1, '博彩',   'GENERAL', 'EXACT', 'BLOCK', NULL,   'INPUT',       1, 1, NOW(), 1, NOW(), 0),
  (12004, 1, '自杀',   'GENERAL', 'EXACT', 'BLOCK', NULL,   'ALL',         1, 1, NOW(), 1, NOW(), 0),
  (12005, 1, '毒品',   'GENERAL', 'EXACT', 'BLOCK', NULL,   'INPUT',       1, 1, NOW(), 1, NOW(), 0),

  -- ========== REPLACE 替换类（隐私脱敏） ==========
  (12010, 1, '密码',   'PRIVACY', 'EXACT', 'REPLACE', '***', 'ALL',         1, 1, NOW(), 1, NOW(), 0),
  (12011, 1, '口令',   'PRIVACY', 'EXACT', 'REPLACE', '***', 'ALL',         1, 1, NOW(), 1, NOW(), 0),
  (12012, 1, '密钥',   'PRIVACY', 'EXACT', 'REPLACE', '***', 'ALL',         1, 1, NOW(), 1, NOW(), 0),
  (12013, 1, 'token',  'PRIVACY', 'FUZZY', 'REPLACE', '***', 'ALL',         1, 1, NOW(), 1, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 12. 权限字典 org_permission：平台共享权限（tenant_id=0，所有租户可见）
--     采用两级树：模块根节点 (parent_id=0) + 子权限 (parent_id=根节点id)
--     ID 雪花生成，范围 2000000000000001000 ~ 2000000000000001999（权限字典段）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_permission`
  (`id`, `tenant_id`, `permission_code`, `permission_name`, `permission_type`, `parent_id`, `sort`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  -- ===== 系统管理模块 (根节点 id=2000000000000001000) =====
  (2000000000000001000, 0, 'system',               '系统管理',     'MENU',  0,  1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001001, 0, 'tenant:view',          '租户查看',     'API',   2000000000000001000, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001002, 0, 'tenant:manage',        '租户管理',     'API',   2000000000000001000, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001003, 0, 'user:view',            '用户查看',     'API',   2000000000000001000, 3, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001004, 0, 'user:manage',          '用户管理',     'API',   2000000000000001000, 4, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001005, 0, 'role:view',            '角色查看',     'API',   2000000000000001000, 5, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001006, 0, 'role:manage',          '角色管理',     'API',   2000000000000001000, 6, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001007, 0, 'dept:view',            '部门查看',     'API',   2000000000000001000, 7, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001008, 0, 'dept:manage',          '部门管理',     'API',   2000000000000001000, 8, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 安全管控模块 (根节点 id=2000000000000001100) =====
  (2000000000000001100, 0, 'security',             '安全管控',     'MENU',  0,  2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001101, 0, 'security:policy:view', '安全策略查看', 'API',   2000000000000001100, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001102, 0, 'security:policy:manage','安全策略管理','API',   2000000000000001100, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001103, 0, 'security:audit:view',  '审计日志查看', 'API',   2000000000000001100, 3, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001104, 0, 'security:mask:view',   '脱敏规则查看', 'API',   2000000000000001100, 4, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001105, 0, 'security:mask:manage', '脱敏规则管理', 'API',   2000000000000001100, 5, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001106, 0, 'security:sensitive:view','敏感词查看','API',  2000000000000001100, 6, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001107, 0, 'security:sensitive:manage','敏感词管理','API',2000000000000001100, 7, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 资源管理模块 (根节点 id=2000000000000001200) =====
  (2000000000000001200, 0, 'resource',             '资源管理',     'MENU',  0,  3, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001201, 0, 'agent:view',           '智能体查看',   'API',   2000000000000001200, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001202, 0, 'agent:create',         '智能体创建',   'API',   2000000000000001200, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001203, 0, 'agent:edit',           '智能体编辑',   'API',   2000000000000001200, 3, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001204, 0, 'agent:delete',         '智能体删除',   'API',   2000000000000001200, 4, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001205, 0, 'agent:publish',        '智能体发布',   'API',   2000000000000001200, 5, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001206, 0, 'agent:review',         '智能体审核',   'API',   2000000000000001200, 6, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001207, 0, 'skill:view',           '技能查看',     'API',   2000000000000001200, 7, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001208, 0, 'skill:create',         '技能创建',     'API',   2000000000000001200, 8, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001209, 0, 'skill:manage',         '技能管理',     'API',   2000000000000001200, 9, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001210, 0, 'mcp:view',             'MCP查看',      'API',   2000000000000001200, 10, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001211, 0, 'mcp:manage',           'MCP管理',      'API',   2000000000000001200, 11, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001212, 0, 'tool:view',            '工具查看',     'API',   2000000000000001200, 12, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001213, 0, 'tool:manage',          '工具管理',     'API',   2000000000000001200, 13, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001214, 0, 'kb:view',              '知识库查看',   'API',   2000000000000001200, 14, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001215, 0, 'kb:create',            '知识库创建',   'API',   2000000000000001200, 15, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001216, 0, 'kb:manage',            '知识库管理',   'API',   2000000000000001200, 16, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 沙箱管理模块 (根节点 id=2000000000000001300) =====
  (2000000000000001300, 0, 'sandbox',              '沙箱管理',     'MENU',  0,  4, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001301, 0, 'sandbox:view',         '沙箱查看',     'API',   2000000000000001300, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001302, 0, 'sandbox:manage',       '沙箱管理',     'API',   2000000000000001300, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 观测中心模块 (根节点 id=2000000000000001400) =====
  (2000000000000001400, 0, 'observe',              '观测中心',     'MENU',  0,  5, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001401, 0, 'observe:view',         '观测查看',     'API',   2000000000000001400, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 模型管理模块 (根节点 id=2000000000000001500) =====
  (2000000000000001500, 0, 'model',                '模型管理',     'MENU',  0,  6, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001501, 0, 'model:view',           '模型查看',     'API',   2000000000000001500, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001502, 0, 'model:manage',         '模型管理',     'API',   2000000000000001500, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  -- ===== 审批/HITL模块 (根节点 id=2000000000000001700) =====
  (2000000000000001700, 0, 'hitl',                 '审批管理',     'MENU',  0,  8, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001701, 0, 'hitl:view',            '审批查看',     'API',   2000000000000001700, 1, 'NORMAL', 1, NOW(), NULL, NOW(), 0),
  (2000000000000001702, 0, 'hitl:approve',         '审批操作',     'API',   2000000000000001700, 2, 'NORMAL', 1, NOW(), NULL, NOW(), 0);

-- -----------------------------------------------------------------------------
-- 13. 角色-权限关联 org_role_permission：DEFAULT 租户 (tenant_id=1)
--     角色定义见第 2 节，角色 ID 1~7 对应 SUPER_ADMIN ~ EMPLOYEE
--     权限 ID 见第 12 节（tenant_id=0 的平台共享权限，全租户可见可关联）
--     ID 雪花生成，范围 2000000000100002000 ~ 2000000000100002999
-- -----------------------------------------------------------------------------
-- SUPER_ADMIN (role_id=1): 拥有所有权限
-- 使用 ROW_NUMBER() 替代 @rownum（MySQL 8.0+ 窗口函数，避免用户变量在 INSERT...SELECT 中的兼容性问题）
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
SELECT
  2000000000100002000 + ROW_NUMBER() OVER (ORDER BY p.id),
  1, 1, p.id, 1, NOW(), NULL, NOW(), 0
FROM org_permission p WHERE p.tenant_id = 0 AND p.deleted = 0;

-- ENTERPRISE_ADMIN (role_id=2): 资源管理 + 用户/角色/部门 + 审计 + 沙箱 + 预算 + 观测 + 模型管理 + HITL
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  -- 系统管理：用户/角色/部门（不含租户管理）
  (2000000000100002101, 1, 2, 2000000000000001000, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002102, 1, 2, 2000000000000001003, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002103, 1, 2, 2000000000000001004, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002104, 1, 2, 2000000000000001005, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002105, 1, 2, 2000000000000001006, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002106, 1, 2, 2000000000000001007, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002107, 1, 2, 2000000000000001008, 1, NOW(), NULL, NOW(), 0),
  -- 安全管控：仅审计查看
  (2000000000100002108, 1, 2, 2000000000000001100, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002109, 1, 2, 2000000000000001103, 1, NOW(), NULL, NOW(), 0),
  -- 资源管理：全部
  (2000000000100002110, 1, 2, 2000000000000001200, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002111, 1, 2, 2000000000000001201, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002112, 1, 2, 2000000000000001202, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002113, 1, 2, 2000000000000001203, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002114, 1, 2, 2000000000000001204, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002115, 1, 2, 2000000000000001205, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002116, 1, 2, 2000000000000001207, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002117, 1, 2, 2000000000000001208, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002118, 1, 2, 2000000000000001209, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002119, 1, 2, 2000000000000001210, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002120, 1, 2, 2000000000000001211, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002121, 1, 2, 2000000000000001212, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002122, 1, 2, 2000000000000001213, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002123, 1, 2, 2000000000000001214, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002124, 1, 2, 2000000000000001215, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002125, 1, 2, 2000000000000001216, 1, NOW(), NULL, NOW(), 0),
  -- 沙箱/观测/模型/HITL
  (2000000000100002126, 1, 2, 2000000000000001300, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002127, 1, 2, 2000000000000001301, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002128, 1, 2, 2000000000000001302, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002129, 1, 2, 2000000000000001400, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002130, 1, 2, 2000000000000001401, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002131, 1, 2, 2000000000000001500, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002132, 1, 2, 2000000000000001501, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002133, 1, 2, 2000000000000001502, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002137, 1, 2, 2000000000000001700, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002138, 1, 2, 2000000000000001701, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002139, 1, 2, 2000000000000001702, 1, NOW(), NULL, NOW(), 0);

-- SECURITY_ADMIN (role_id=3): 安全策略/审计日志/脱敏规则/敏感词 + 安全管控接口访问
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (2000000000100002201, 1, 3, 2000000000000001100, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002202, 1, 3, 2000000000000001101, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002203, 1, 3, 2000000000000001102, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002204, 1, 3, 2000000000000001103, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002205, 1, 3, 2000000000000001104, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002206, 1, 3, 2000000000000001105, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002207, 1, 3, 2000000000000001106, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002208, 1, 3, 2000000000000001107, 1, NOW(), NULL, NOW(), 0),
  -- 观测查看（安全监控需要）
  (2000000000100002209, 1, 3, 2000000000000001400, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002210, 1, 3, 2000000000000001401, 1, NOW(), NULL, NOW(), 0);

-- RESOURCE_ADMIN (role_id=4): 资源管理全部 + 审核
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (2000000000100002301, 1, 4, 2000000000000001200, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002302, 1, 4, 2000000000000001201, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002303, 1, 4, 2000000000000001202, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002304, 1, 4, 2000000000000001203, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002305, 1, 4, 2000000000000001204, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002306, 1, 4, 2000000000000001205, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002307, 1, 4, 2000000000000001206, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002308, 1, 4, 2000000000000001207, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002309, 1, 4, 2000000000000001208, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002310, 1, 4, 2000000000000001209, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002311, 1, 4, 2000000000000001210, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002312, 1, 4, 2000000000000001211, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002313, 1, 4, 2000000000000001212, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002314, 1, 4, 2000000000000001213, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002315, 1, 4, 2000000000000001214, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002316, 1, 4, 2000000000000001215, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002317, 1, 4, 2000000000000001216, 1, NOW(), NULL, NOW(), 0);

-- AGENT_REVIEWER (role_id=5): 智能体审核 + 资源查看
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (2000000000100002401, 1, 5, 2000000000000001200, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002402, 1, 5, 2000000000000001201, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002403, 1, 5, 2000000000000001206, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002404, 1, 5, 2000000000000001207, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002405, 1, 5, 2000000000000001210, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002406, 1, 5, 2000000000000001212, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002407, 1, 5, 2000000000000001214, 1, NOW(), NULL, NOW(), 0);

-- AGENT_CREATOR (role_id=6): 智能体创建/编辑/发布 + 资源查看
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (2000000000100002501, 1, 6, 2000000000000001200, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002502, 1, 6, 2000000000000001201, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002503, 1, 6, 2000000000000001202, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002504, 1, 6, 2000000000000001203, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002505, 1, 6, 2000000000000001204, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002506, 1, 6, 2000000000000001205, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002507, 1, 6, 2000000000000001207, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002508, 1, 6, 2000000000000001210, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002509, 1, 6, 2000000000000001212, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002510, 1, 6, 2000000000000001214, 1, NOW(), NULL, NOW(), 0);

-- EMPLOYEE (role_id=7): 资源查看 + 智能体创建(基础)
INSERT IGNORE INTO `org_role_permission`
  (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`)
VALUES
  (2000000000100002601, 1, 7, 2000000000000001200, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002602, 1, 7, 2000000000000001201, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002603, 1, 7, 2000000000000001202, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002604, 1, 7, 2000000000000001207, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002605, 1, 7, 2000000000000001210, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002606, 1, 7, 2000000000000001212, 1, NOW(), NULL, NOW(), 0),
  (2000000000100002607, 1, 7, 2000000000000001214, 1, NOW(), NULL, NOW(), 0);

SET FOREIGN_KEY_CHECKS = 1;

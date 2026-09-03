-- =============================================================================
-- Aegis Platform - 数据库表结构初始化 (DDL)
-- -----------------------------------------------------------------------------
-- 基准：以当前运行 MySQL 实际表结构导出，与后端 MyBatis-Plus 实体 (PO) 一致。
-- 执行：MySQL 容器首次启动时由 /docker-entrypoint-initdb.d 自动执行；
--       也可手动执行：mysql -uroot -p < 01_schema_init.sql
-- 表前缀：ten_(租户) org_(组织) res_(资源) agent_(智能体) sess_(会话)
--         sec_(安全) sbx_(沙箱) mon_(监控) att_(附件) eval_(评测) model_(模型)
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
CREATE DATABASE IF NOT EXISTS `aegis` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `aegis`;

DROP TABLE IF EXISTS `agent_api`;
DROP TABLE IF EXISTS `agent_api`;
CREATE TABLE `agent_api` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联AgentDef主键',
  `api_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API名称，展示用',
  `api_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API路径，租户内唯一，格式 /api/v1/agents/{code}/invoke',
  `http_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'HTTP方法：GET/POST，默认POST',
  `auth_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '认证类型：API_KEY/BEARER/OAUTH2/BASIC/NONE',
  `response_mode` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '响应模式：SYNC/ASYNC/SSE',
  `rate_limit` int DEFAULT NULL COMMENT '限流QPS，每秒最大请求数',
  `timeout` int DEFAULT NULL COMMENT '超时时间（秒），默认30',
  `validity_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '有效期类型：PERMANENT（永久）/ DAYS_7（7天）/ DAYS_30（30天）/ CUSTOM（自定义）',
  `valid_until` datetime DEFAULT NULL COMMENT '固定有效期截止时间，validityType非PERMANENT时生效',
  `scope_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '数据出境范围类型：INTERNAL_IP（企业内部白名单IP）/ DEPT（指定部门）/ PARTNER（指定外部合作伙伴）',
  `scope_config` json DEFAULT NULL COMMENT '出境范围配置，JSON格式（含白名单域名/字段等）',
  `api_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API密钥，仅创建时明文返回，存储哈希值',
  `webhook_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '回调地址，异步模式结果回调URL',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API状态：NORMAL（正常）/ DISABLED（停用）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  `deployment_pool_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部署目标沙箱池编码（外键 sbx_pool.pool_code + tenantId），系统智能体必填',
  `reserved_replicas` int DEFAULT '1' COMMENT '该智能体在绑定池内的预留常驻副本数，保证对外 API 冷启动与容量',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '1.0.0' COMMENT 'API 版本号，如 1.0.0',
  `concurrent_limit` int DEFAULT '10' COMMENT '并发请求上限',
  `request_schema` json DEFAULT NULL COMMENT '入参 JSON Schema',
  `response_schema` json DEFAULT NULL COMMENT '出参 JSON Schema',
  `example_request` json DEFAULT NULL COMMENT '示例请求体',
  `example_response` json DEFAULT NULL COMMENT '示例响应体',
  `api_doc_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API 文档 URL',
  `last_tested_at` datetime DEFAULT NULL COMMENT '最近测试时间',
  `bearer_token_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PASSTHROUGH' COMMENT 'Bearer Token 管理模式：STATIC/PASSTHROUGH',
  `bearer_token_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '静态模式下的 Token 值',
  `bearer_jwt_secret` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'JWT 签名密钥/公钥',
  `bearer_jwt_algorithm` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'HS256' COMMENT 'JWT 签名算法',
  `bearer_introspection_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Token introspection 端点',
  `bearer_pass_through` tinyint(1) DEFAULT '0' COMMENT '是否将 Token 透传给下游 Agent 服务',
  `pool_allocate_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'sandbox allocate status PENDING/ALLOCATED/FAILED',
  `allocate_time` datetime DEFAULT NULL COMMENT 'sandbox pool allocate finish time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_api_path` (`tenant_id`,`api_path`),
  UNIQUE KEY `uk_api_path_method` (`tenant_id`,`api_path`,`http_method`),
  KEY `idx_agent_api_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体开放API实体，支持外部系统调用';
DROP TABLE IF EXISTS `agent_api_key`;
DROP TABLE IF EXISTS `agent_api_key`;
CREATE TABLE `agent_api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint NOT NULL COMMENT '智能体 ID',
  `api_id` bigint NOT NULL COMMENT 'API 配置 ID',
  `api_key_hash` varchar(128) NOT NULL COMMENT 'API Key SHA-256 哈希',
  `key_label` varchar(64) DEFAULT '?Key' COMMENT 'Key 标签',
  `status` enum('ACTIVE','REVOKED','EXPIRED') DEFAULT 'ACTIVE' COMMENT '状态',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间',
  `last_used_at` datetime DEFAULT NULL COMMENT '最后使用时间',
  `rotate_from` bigint DEFAULT NULL COMMENT '轮换自旧 Key ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除标志',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  PRIMARY KEY (`id`),
  KEY `idx_agent_api` (`agent_id`,`api_id`,`status`),
  KEY `idx_key_hash` (`api_key_hash`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2094961547052097538 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体 API Key 生命周期管理';
DROP TABLE IF EXISTS `agent_binding`;
DROP TABLE IF EXISTS `agent_binding`;
CREATE TABLE `agent_binding` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联AgentDef主键',
  `agent_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体版本号，绑定归属的版本',
  `resource_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源类型：SKILL/KNOWLEDGE_BASE/MCP/TOOL/DATASET',
  `resource_id` bigint DEFAULT NULL COMMENT '资源ID，关联具体资源主键',
  `resource_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源版本，固定绑定为具体版本号，动态绑定为latest',
  `binding_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '绑定类型：FIXED（固定绑定，版本锁定）/ DYNAMIC（动态加载，运行时解析）',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用：true-生效 false-停用，临时禁用不删除绑定',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_binding_resource` (`tenant_id`,`agent_id`,`resource_type`,`resource_id`),
  KEY `idx_agent_binding_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体资源绑定实体，关联SKILL/MCP/知识库/数据集';
DROP TABLE IF EXISTS `agent_config`;
DROP TABLE IF EXISTS `agent_config`;
CREATE TABLE `agent_config` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联AgentDef主键',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '配置版本号，与AgentDef.version对应',
  `system_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '系统提示词，定义智能体角色与行为约束',
  `model_tier` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型档位：LIGHT（轻量）/ STANDARD（标准）/ STRONG（强力）',
  `temperature` decimal(20,4) DEFAULT NULL COMMENT '温度参数，0-2，值越高输出越发散，0为确定性输出',
  `memory_strategy` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '记忆策略：SESSION_LEVEL（会话级）/ LONG_TERM（长期，用户归档）',
  `max_turns` int DEFAULT NULL COMMENT '最大对话轮数，超限后提示新建会话',
  `permission_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '权限模式',
  `enable_plan_mode` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用计划模式',
  `compaction_threshold` int DEFAULT NULL COMMENT '上下文压缩阈值',
  `memory_flush_strategy` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '记忆刷写策略',
  `enabled_tools` json DEFAULT NULL COMMENT '启用工具ID列表，JSON数组格式（如 ["t1","t2"]），空表示不启用任何工具',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_config_version` (`tenant_id`,`agent_id`,`version`),
  KEY `idx_agent_config_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体配置实体，承载提示词与模型参数';
DROP TABLE IF EXISTS `agent_def`;
DROP TABLE IF EXISTS `agent_def`;
CREATE TABLE `agent_def` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体编码，租户内唯一，创建后不可修改',
  `agent_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体名称，展示用',
  `agent_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体类型：UNIVERSAL（通用智能体，平台唯一）/ APPLICATION（应用智能体，用户创建）',
  `icon` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体图标URL',
  `color` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '主题色，前端展示用，十六进制色值',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '智能体描述，介绍智能体能力与适用场景',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体分类，市场检索用',
  `life_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生命周期状态：DRAFT→REVIEWING→PUBLISHED→ARCHIVED',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前版本号，每次发布递增',
  `author_user_id` bigint DEFAULT NULL COMMENT '创建者用户ID，关联User主键',
  `author_dept_id` bigint DEFAULT NULL COMMENT '创建者部门ID，关联Department主键',
  `subs_count` int DEFAULT NULL COMMENT '订阅数，缓存统计，用于市场排序',
  `published_time` datetime DEFAULT NULL COMMENT '发布时间，审核通过时记录',
  `visibility` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'TENANT' COMMENT '发布可见范围：TENANT（本租户可见，默认）/ PUBLIC（全平台可见，跨租户市场）',
  `archived_time` datetime DEFAULT NULL COMMENT '归档时间，下架时记录',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号，每次更新递增',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  `usage_scenario` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PERSONAL' COMMENT '使用场景：PERSONAL-个人使用/SHARED-共享发布',
  `governance_tier` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'STANDARD' COMMENT '治理档位：STANDARD-标准/ENHANCED-增强/STRICT-严格，取代安全级别/护栏级别/规划模式',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_def_code` (`tenant_id`,`agent_code`),
  UNIQUE KEY `uk_tenant_universal` (`tenant_id`,`agent_type`,`agent_code`),
  KEY `idx_agent_def_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体定义实体，智能体域核心聚合根';
DROP TABLE IF EXISTS `agent_memory`;
DROP TABLE IF EXISTS `agent_memory`;
CREATE TABLE `agent_memory` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联AgentDef主键',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID，关联User主键，记忆按用户隔离',
  `memory_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '记忆类型：USER_PROFILE（用户画像）/ TASK_SUMMARY（任务摘要）/ KEY_FACT（关键事实）',
  `memory_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '记忆键，结构化标识，如 profile.role / fact.project_name',
  `memory_value` json DEFAULT NULL COMMENT '记忆值，JSON格式存储结构化内容',
  `editable` tinyint(1) DEFAULT NULL COMMENT '是否用户可编辑：true-用户可修改 false-系统管理不可手动改',
  `source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '记忆来源：auto-自动提取 manual-手动录入 import-外部导入',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_key` (`tenant_id`,`agent_id`,`user_id`,`memory_type`,`memory_key`),
  KEY `idx_agent_memory_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体记忆实体，跨会话持久化用户上下文';
DROP TABLE IF EXISTS `agent_subscription`;
DROP TABLE IF EXISTS `agent_subscription`;
CREATE TABLE `agent_subscription` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联AgentDef主键',
  `user_id` bigint DEFAULT NULL COMMENT '订阅用户ID，关联User主键',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '订阅状态：ACTIVE（已订阅）/ UNSUBSCRIBED（已退订）',
  `subscribe_time` datetime DEFAULT NULL COMMENT '订阅时间，发起订阅时记录',
  `unsubscribe_time` datetime DEFAULT NULL COMMENT '退订时间，用户退订时记录',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_sub_user` (`tenant_id`,`agent_id`,`user_id`),
  KEY `idx_agent_sub_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体订阅关系实体（可见即可订阅，无审批）';
DROP TABLE IF EXISTS `agent_workspace_material`;
DROP TABLE IF EXISTS `agent_workspace_material`;
CREATE TABLE `agent_workspace_material` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint NOT NULL COMMENT '智能体ID，关联agent_def.id',
  `agent_version` int NOT NULL DEFAULT '0' COMMENT 'agent version',
  `user_id` bigint NOT NULL DEFAULT '0' COMMENT '用户ID（通用智能体按用户隔离，应用/系统智能体为0）',
  `isolation_scope` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '隔离作用域：USER / AGENT / GLOBAL',
  `workspace_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工作区路径',
  `material_fingerprint` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '物化指纹（绑定资源版本号的hash）',
  `binding_snapshot` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '绑定快照（JSON，记录当时的绑定列表）',
  `last_materialized_at` datetime DEFAULT NULL COMMENT '最后物化时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_agent_user` (`tenant_id`,`agent_id`,`user_id`),
  KEY `idx_material_fingerprint` (`material_fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体工作区物化指纹，记录每次物化的快照用于增量检测';
DROP TABLE IF EXISTS `att_file_meta`;
DROP TABLE IF EXISTS `att_file_meta`;
CREATE TABLE `att_file_meta` (
  `file_id` varchar(64) NOT NULL COMMENT 'file id (uuid no hyphen), pk',
  `tenant_id` bigint DEFAULT NULL COMMENT 'tenant id',
  `filename` varchar(255) DEFAULT NULL COMMENT 'original filename',
  `ext` varchar(16) DEFAULT NULL COMMENT 'extension with dot',
  `size_bytes` bigint DEFAULT NULL COMMENT 'file size in bytes',
  `content_type` varchar(128) DEFAULT NULL COMMENT 'mime type',
  `storage_key` varchar(512) DEFAULT NULL COMMENT 'minio object key',
  `mime_verified` tinyint DEFAULT '0' COMMENT 'mime verified 1=yes 0=no',
  `user_id` bigint DEFAULT NULL COMMENT 'uploader user id',
  `create_by` bigint DEFAULT NULL COMMENT 'creator id',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  `deleted` tinyint DEFAULT '0' COMMENT 'logical delete flag',
  PRIMARY KEY (`file_id`),
  KEY `idx_afm_tenant_user` (`tenant_id`,`user_id`),
  KEY `idx_afm_storage_key` (`storage_key`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='attachment file metadata';
DROP TABLE IF EXISTS `att_parse_cache`;
DROP TABLE IF EXISTS `att_parse_cache`;
CREATE TABLE `att_parse_cache` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '从 att_file_meta 继承的租户ID',
  `file_id` varchar(128) NOT NULL,
  `parse_version` varchar(32) DEFAULT NULL,
  `content_type` varchar(128) DEFAULT NULL,
  `parsed_text` longtext,
  `parsed_metadata` json DEFAULT NULL,
  `char_count` int DEFAULT NULL,
  `token_estimate` int DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  `create_by` bigint DEFAULT NULL COMMENT 'creator id',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `content_hash` varchar(64) DEFAULT NULL COMMENT 'file content SHA-256 hash',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_version` (`file_id`,`parse_version`,`deleted`),
  KEY `idx_file` (`file_id`),
  KEY `idx_att_parse_cache_tenant` (`tenant_id`,`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='附件解析缓存';
DROP TABLE IF EXISTS `eval_task`;
DROP TABLE IF EXISTS `eval_task`;
CREATE TABLE `eval_task` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务唯一标识，UUID字符串，用于全链路追踪',
  `agent_id` bigint DEFAULT NULL COMMENT '被评测智能体ID，关联agent_def.id',
  `agent_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体版本，评测时的智能体版本号，保证评测可复现',
  `suite_id` bigint DEFAULT NULL COMMENT '测试套件ID，关联test_suite.id，评测使用的测试集',
  `trigger_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '触发类型：PRE_RELEASE（版本发布前）/ MANUAL（手动）/ SCHEDULED（定时回归）',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务状态：COMPLETED（已完成）/ IN_PROGRESS（进行中）/ QUEUED（排队中）',
  `total_count` int DEFAULT NULL COMMENT '测试用例总数，本次评测执行的TestCase总数',
  `passed_count` int DEFAULT NULL COMMENT '通过数，评测通过的TestCase数量',
  `accuracy` decimal(20,4) DEFAULT NULL COMMENT '准确率，0-1之间，passedCount / totalCount',
  `avg_latency_ms` int DEFAULT NULL COMMENT '平均延迟，单位毫秒，所有测试用例的平均响应时间',
  `token_used` bigint DEFAULT NULL COMMENT 'Token消耗，本次评测累计消耗的token总量',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间，评测任务开始执行的时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间，评测任务执行完成的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_eval_task_id` (`tenant_id`,`task_id`),
  KEY `idx_eval_task_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测任务实体';
DROP TABLE IF EXISTS `eval_test_case`;
DROP TABLE IF EXISTS `eval_test_case`;
CREATE TABLE `eval_test_case` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `suite_id` bigint DEFAULT NULL COMMENT '所属测试套件ID，关联test_suite.id',
  `case_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用例唯一标识，套件内唯一，用于用例检索与引用',
  `input_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '输入类型：TEXT（文本）/ FILE（文件）',
  `input_content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '输入内容，依据inputType不同结构不同，如纯文本或JSON多模态描述',
  `expected_output` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '期望输出，用于与实际输出比对，可以是文本或JSON结构',
  `expected_tool` json DEFAULT NULL COMMENT '期望工具，JSON数组字符串，期望智能体调用的工具列表',
  `eval_method` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '评估方法：EXACT_MATCH（精确匹配）/ KEYWORD（关键词包含）/ LLM_SCORE（LLM评分）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_test_case_id` (`tenant_id`,`suite_id`,`case_id`),
  KEY `idx_test_case_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例实体';
DROP TABLE IF EXISTS `eval_test_suite`;
DROP TABLE IF EXISTS `eval_test_suite`;
CREATE TABLE `eval_test_suite` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `suite_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '套件名称，长度不超过128，标识测试套件用途',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联agent_def.id，套件服务的智能体',
  `case_count` int DEFAULT NULL COMMENT '用例数量，套件内测试用例总数，由系统自动统计',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本号，语义化版本如1.0.0，支持套件的版本演进与回溯',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '套件描述，长度不超过512，说明套件覆盖场景与测试目标',
  `updated_time` datetime DEFAULT NULL COMMENT '更新时间，套件最近一次修改的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_test_suite_name` (`tenant_id`,`agent_id`,`suite_name`),
  KEY `idx_test_suite_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试套件实体';
DROP TABLE IF EXISTS `model_def`;
DROP TABLE IF EXISTS `model_def`;
CREATE TABLE `model_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `provider_id` bigint DEFAULT NULL COMMENT '所属提供商ID，关联model_provider.id',
  `model_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型唯一编码，提供商内唯一，如gpt-4、claude-3-opus、qwen-max',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型展示名称，长度不超过128，如"GPT-4 Turbo"、"通义千问-Max"',
  `tier` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型层级：LIGHT（轻量）/ STANDARD（标准）/ STRONG（强力），用于路由选择',
  `model_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TEXT' COMMENT '模型用途：TEXT/MULTIMODAL/EMBEDDING/VISION',
  `context_window` int DEFAULT NULL COMMENT '上下文窗口大小，单位token，如8192、128000，影响可处理输入长度',
  `input_cost` decimal(20,4) DEFAULT NULL COMMENT '输入计费单价，单位元/千token，用于成本核算',
  `output_cost` decimal(20,4) DEFAULT NULL COMMENT '输出计费单价，单位元/千token，用于成本核算',
  `qps_limit` int DEFAULT NULL COMMENT 'QPS限制，该模型最大允许请求速率，取值范围1-5000',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：ENABLED（启用）/ DISABLED（禁用），管理员控制模型可用性',
  `latency` decimal(20,4) DEFAULT NULL COMMENT '平均延迟，单位毫秒，由系统监控统计，用于路由决策',
  `quality_grade` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '质量等级：S_PLUS/S/A_PLUS/A/B_PLUS，综合评估模型输出质量',
  `capabilities` json DEFAULT NULL COMMENT '模型能力矩阵（JSON: multimodal/document/vision_description等）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_def_code` (`provider_id`,`model_code`),
  KEY `idx_model_def_create_time` (`create_time`),
  KEY `idx_model_type` (`model_type`)
) ENGINE=InnoDB AUTO_INCREMENT=2095318729404731394 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型定义实体';
DROP TABLE IF EXISTS `model_provider`;
DROP TABLE IF EXISTS `model_provider`;
CREATE TABLE `model_provider` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `provider_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提供商唯一编码，全局唯一，如openai、anthropic、qwen，长度不超过64',
  `provider_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提供商展示名称，长度不超过128，如"OpenAI"、"通义千问"',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：ACTIVE（已接入）/ PENDING（待接入），管理员控制提供商可用性',
  `endpoint` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '服务接入端点，API基础URL，如https://api.openai.com/v1',
  `api_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'API密钥，敏感字段，存储时加密，用于服务端鉴权',
  `qps_limit` int DEFAULT NULL COMMENT 'QPS限制，该提供商最大允许请求速率，取值范围1-10000',
  `monthly_quota` decimal(20,4) DEFAULT NULL COMMENT '月度配额，预算上限，单位元，超出将触发预算告警或熔断',
  `used_quota` decimal(20,4) DEFAULT NULL COMMENT '已用配额，当月累计消耗，单位元，由系统实时统计',
  `color` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '品牌颜色，十六进制色值如#10A37F，用于前端展示标识',
  `model_count` int DEFAULT NULL COMMENT '模型数量，该提供商下可用模型总数，由系统自动统计',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_code` (`provider_code`),
  KEY `idx_model_provider_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型提供商实体';
DROP TABLE IF EXISTS `model_rate_limit`;
DROP TABLE IF EXISTS `model_rate_limit`;
CREATE TABLE `model_rate_limit` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '限流作用域：PLATFORM（全平台）/ DEPT（部门）/ USER（个人）',
  `scope_target_id` bigint DEFAULT NULL COMMENT '限流对象ID，依据scope关联对应表主键',
  `light_qps` int DEFAULT NULL COMMENT '轻量模型QPS限制，light层级最大请求速率',
  `standard_qps` int DEFAULT NULL COMMENT '标准模型QPS限制，standard层级最大请求速率',
  `strong_qps` int DEFAULT NULL COMMENT '强力模型QPS限制，strong层级最大请求速率',
  `total_qps` int DEFAULT NULL COMMENT '总QPS限制，所有层级模型合计最大请求速率',
  `used_qps` int DEFAULT NULL COMMENT '已用QPS，当前实时请求速率，由系统监控统计更新',
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '超限动作：ALERT（告警）/ LIMIT（限流）/ PASS（放行）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_rate_limit` (`tenant_id`,`scope`,`scope_target_id`),
  KEY `idx_model_rate_limit_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型限流策略实体';
DROP TABLE IF EXISTS `model_route`;
DROP TABLE IF EXISTS `model_route`;
CREATE TABLE `model_route` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `tier` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型层级：LIGHT（轻量）/ STANDARD（标准）/ STRONG（强力），路由按层级匹配模型',
  `default_model_id` bigint DEFAULT NULL COMMENT '默认模型ID，关联model_def.id，该层级场景下的首选模型',
  `candidate_model_ids` json DEFAULT NULL COMMENT '候选模型ID列表，JSON数组格式（如 [1,2,3]），用于负载均衡与故障切换',
  `degrade_chain_id` bigint DEFAULT NULL COMMENT '降级链ID，关联model_degrade_chain.id，故障时执行的降级策略',
  `scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '限流作用域：PLATFORM（全平台）/ DEPT（部门）/ USER（个人）',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '路由描述，长度不超过512，说明路由策略适用场景与配置理由',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_route` (`tenant_id`,`tier`,`scope`),
  KEY `idx_model_route_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型路由实体';
DROP TABLE IF EXISTS `mon_audit_log`;
DROP TABLE IF EXISTS `mon_audit_log`;
CREATE TABLE `mon_audit_log` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `log_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '日志类型：OPERATION（操作）/ SECURITY（安全）/ DATA（数据）/ SESSION（会话）/ TOOL（工具）/ EXPORT（导出）',
  `user_id` bigint DEFAULT NULL COMMENT '操作人用户ID，关联org_user.id',
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人用户名，冗余存储便于审计列表展示',
  `operation` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作类型，如CREATE_AGENT/UPDATE_SKILL/DELETE_KB，标识具体操作',
  `resource_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源类型，如AGENT/SKILL/KNOWLEDGE_BASE，被操作资源种类',
  `resource_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源名称，被操作资源的名称，便于审计追溯',
  `detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '操作详情，JSON字符串，记录操作的具体参数与变更内容',
  `result` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作结果：SUCCESS（成功）/ BLOCKED（拦截）/ ALERT（告警）/ RECORDED（已记录）',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '客户端IP，操作发起的IP地址，用于安全审计',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'User-Agent，客户端标识，记录操作来源设备与浏览器',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '链路追踪ID，用于全链路日志关联与排查',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID（结构化字段）',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID（结构化字段）',
  `retention_days` int DEFAULT NULL COMMENT '保留天数，日志保留时长，依据logType不同默认值不同，过期自动清理',
  `occur_time` datetime DEFAULT NULL COMMENT '发生时间，操作实际发生的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_audit_log_user` (`tenant_id`,`user_id`),
  KEY `idx_audit_log_occur_time` (`occur_time`),
  KEY `idx_audit_log_create_time` (`create_time`),
  KEY `idx_audit_session` (`tenant_id`,`session_id`),
  KEY `idx_audit_agent` (`tenant_id`,`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志实体';
DROP TABLE IF EXISTS `mon_backup_record`;
DROP TABLE IF EXISTS `mon_backup_record`;
CREATE TABLE `mon_backup_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `backup_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '备份ID',
  `backup_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FULL/INCREMENTAL',
  `size_bytes` bigint DEFAULT '0' COMMENT '备份大小(字节)',
  `duration_sec` int DEFAULT '0' COMMENT '耗时(秒)',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/RUNNING/FAILED',
  `location` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '存储位置',
  `occur_time` datetime NOT NULL COMMENT '执行时间',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_occur_time` (`occur_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='备份记录';
DROP TABLE IF EXISTS `mon_drill_record`;
DROP TABLE IF EXISTS `mon_drill_record`;
CREATE TABLE `mon_drill_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `drill_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '演练ID',
  `drill_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'FAILOVER/RECOVERY/FULLCHAIN',
  `duration_sec` int DEFAULT '0' COMMENT '耗时(秒)',
  `result` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'PASS/PARTIAL/FAIL',
  `report` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '演练报告',
  `occur_time` datetime NOT NULL COMMENT '发生时间',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_drill_id` (`drill_id`),
  KEY `idx_occur_time` (`occur_time`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='灾备演练记录';
DROP TABLE IF EXISTS `mon_failover_record`;
DROP TABLE IF EXISTS `mon_failover_record`;
CREATE TABLE `mon_failover_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '触发原因',
  `from_node` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '源节点',
  `to_node` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标节点',
  `duration_sec` int DEFAULT '0' COMMENT '耗时(秒)',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/FAILED/ROLLBACK',
  `trigger_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AUTO/MANUAL',
  `occur_time` datetime NOT NULL COMMENT '发生时间',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除标志：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_occur_time` (`occur_time`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障切换记录';
DROP TABLE IF EXISTS `mon_span`;
DROP TABLE IF EXISTS `mon_span`;
CREATE TABLE `mon_span` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '从 mon_trace 继承的租户ID',
  `trace_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属链路ID',
  `span_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'span标识',
  `parent_span_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '父span',
  `span_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SPAN类型',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名/模型名/操作名',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/SKIPPED',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` int DEFAULT NULL COMMENT '耗时(ms)',
  `input_summary` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入参摘要',
  `output_summary` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '出参摘要',
  `token_input` int DEFAULT '0' COMMENT '输入token',
  `token_output` int DEFAULT '0' COMMENT '输出token',
  `cost_amount` decimal(12,6) DEFAULT '0.000000' COMMENT '成本',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败详情',
  `meta` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '扩展属性JSON',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_by` bigint DEFAULT NULL COMMENT 'creator id',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT NULL COMMENT 'update time',
  `deleted` tinyint DEFAULT '0' COMMENT 'logical delete flag',
  PRIMARY KEY (`id`),
  KEY `idx_span_trace` (`trace_id`),
  KEY `idx_span_type` (`span_type`),
  KEY `idx_span_name` (`name`),
  KEY `idx_span_time` (`start_time`),
  KEY `idx_mon_span_tenant_trace` (`tenant_id`,`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行步骤表';
DROP TABLE IF EXISTS `mon_trace`;
DROP TABLE IF EXISTS `mon_trace`;
CREATE TABLE `mon_trace` (
  `id` bigint NOT NULL COMMENT '主键',
  `trace_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '链路ID',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '所属会话ID',
  `agent_id` bigint DEFAULT NULL COMMENT '执行智能体ID',
  `agent_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体名称',
  `user_id` bigint DEFAULT NULL COMMENT '发起用户ID',
  `user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `api_path` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '入口路径',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED/TIMEOUT',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` int DEFAULT NULL COMMENT '总耗时(ms)',
  `token_input` int DEFAULT '0' COMMENT '聚合输入token',
  `token_output` int DEFAULT '0' COMMENT '聚合输出token',
  `cost_amount` decimal(12,6) DEFAULT '0.000000' COMMENT '聚合成本',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `span_count` int DEFAULT '0' COMMENT 'span数量',
  `sse_event_count` int DEFAULT '0' COMMENT 'SSE事件数',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` int DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_id` (`trace_id`),
  KEY `idx_trace_session` (`session_id`),
  KEY `idx_trace_user` (`user_id`),
  KEY `idx_trace_agent` (`agent_id`),
  KEY `idx_trace_time` (`start_time`),
  KEY `idx_trace_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='执行链路主表';
DROP TABLE IF EXISTS `org_department`;
DROP TABLE IF EXISTS `org_department`;
CREATE TABLE `org_department` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `dept_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门名称，租户内可重复，同级建议唯一',
  `parent_id` bigint DEFAULT NULL COMMENT '父部门ID，根部门为0或null',
  `dept_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门完整路径，格式 /root/parent/self/，用于祖先/后代查询',
  `dept_level` int DEFAULT NULL COMMENT '部门层级，取值1-5，根部门为1',
  `sort` int DEFAULT NULL COMMENT '同级排序号，升序排列',
  `leader_user_id` bigint DEFAULT NULL COMMENT '部门负责人用户ID，关联User主键',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '部门状态：NORMAL（正常）/ DISABLED（禁用），禁用后不在组织树展示',
  `sync_source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '同步来源：HR（HR同步）/ OA（OA同步）/ LDAP（LDAP同步）/ MANUAL（手动创建）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_path` (`tenant_id`,`dept_path`),
  KEY `idx_dept_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门实体，树形组织架构节点';
DROP TABLE IF EXISTS `org_permission`;
DROP TABLE IF EXISTS `org_permission`;
CREATE TABLE `org_permission` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID，0=平台共享权限（所有租户可见），>0=租户自定义权限',
  `permission_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '权限编码，如 agent:view / tenant:manage，租户内唯一，程序引用标识',
  `permission_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限名称，展示用',
  `permission_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限类型：MENU（菜单）/ BUTTON（按钮）/ API（接口）',
  `parent_id` bigint DEFAULT '0' COMMENT '父权限ID，用于权限树展示，0表示根节点',
  `sort` int DEFAULT '0' COMMENT '同级排序号，升序排列',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'NORMAL' COMMENT '状态：NORMAL（正常）/ DISABLED（禁用）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`tenant_id`,`permission_code`),
  KEY `idx_perm_parent` (`parent_id`),
  KEY `idx_perm_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限字典实体，数据驱动 RBAC 的权限点定义（平台共享 tenant_id=0 + 租户自建）';
DROP TABLE IF EXISTS `org_role`;
DROP TABLE IF EXISTS `org_role`;
CREATE TABLE `org_role` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `role_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色编码，租户内唯一，程序引用标识，创建后不可修改',
  `role_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色名称，展示用',
  `role_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色类型：PLATFORM（平台角色，系统操作权限）/ RESOURCE（资源角色，资源访问权限）',
  `parent_role_id` bigint DEFAULT NULL COMMENT '父角色ID，用于角色继承，null表示无父角色',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '角色描述，说明角色职责与权限范围',
  `sort` int DEFAULT NULL COMMENT '同级排序号，升序排列',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '角色状态：NORMAL（正常）/ DISABLED（禁用），禁用后关联用户失去该角色权限',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`tenant_id`,`role_code`),
  KEY `idx_role_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色实体，权限控制的核心载体';
DROP TABLE IF EXISTS `org_role_permission`;
DROP TABLE IF EXISTS `org_role_permission`;
CREATE TABLE `org_role_permission` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID，关联 org_role 主键',
  `permission_id` bigint NOT NULL COMMENT '权限ID，关联 org_permission 主键',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`tenant_id`,`role_id`,`permission_id`),
  KEY `idx_role_perm_role` (`tenant_id`,`role_id`),
  KEY `idx_role_perm_perm` (`permission_id`),
  KEY `idx_role_perm_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限关联实体，登录时按用户角色聚合本表得到权限编码列表写入JWT';
DROP TABLE IF EXISTS `org_user`;
DROP TABLE IF EXISTS `org_user`;
CREATE TABLE `org_user` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名，租户内唯一，登录凭证，创建后不可修改',
  `password` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码哈希，BCrypt加密存储，不可逆向',
  `real_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '真实姓名，展示用',
  `emp_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工号，租户内唯一，用于与HR系统对接',
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱，用于通知与密码找回，租户内唯一',
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号，用于MFA与紧急通知，租户内唯一',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像URL',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户状态：NORMAL（正常）/ DISABLED（禁用），禁用后无法登录',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后登录IP，用于安全审计',
  `dept_id` bigint DEFAULT NULL COMMENT '主部门ID，关联Department主键，用户归属的主部门',
  `mfa_enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用MFA双因子认证：true-已启用 false-未启用',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`tenant_id`,`username`),
  UNIQUE KEY `uk_user_email` (`tenant_id`,`email`),
  UNIQUE KEY `uk_user_phone` (`tenant_id`,`phone`),
  UNIQUE KEY `uk_user_emp_no` (`tenant_id`,`emp_no`),
  KEY `idx_user_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户实体，平台用户主体';
DROP TABLE IF EXISTS `org_user_role`;
DROP TABLE IF EXISTS `org_user_role`;
CREATE TABLE `org_user_role` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID，关联User主键',
  `role_id` bigint DEFAULT NULL COMMENT '角色ID，关联Role主键',
  `resource_id` bigint DEFAULT NULL COMMENT '资源ID，资源角色时关联具体资源主键，平台角色为null',
  `resource_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源类型，资源角色时必填，标识资源类别',
  `source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '授权来源：DIRECT（直接授予）/ DEPT_INHERIT（部门继承）/ RESOURCE_AUTH（资源授权）',
  `expire_time` datetime DEFAULT NULL COMMENT '授权过期时间，null表示长期有效，到期自动失效',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_user_role_user` (`tenant_id`,`user_id`),
  KEY `idx_user_role_role` (`tenant_id`,`role_id`),
  KEY `idx_user_role_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联实体';
DROP TABLE IF EXISTS `res_kb_document`;
DROP TABLE IF EXISTS `res_kb_document`;
CREATE TABLE `res_kb_document` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `kb_id` bigint DEFAULT NULL COMMENT '所属知识库ID，关联res_knowledge_base.id',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件名，包含扩展名，长度不超过255',
  `file_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件类型，取值：PDF/DOCX/XLSX/PPTX/TXT/MD/HTML等',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小，单位字节',
  `oss_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'OSS存储键，文件在对象存储中的唯一路径',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理状态：PENDING（待扫描）/ SCANNING（扫描中）/ CHUNKING（切片中）/ CHUNKED（已切片）/ FAILED（失败）',
  `chunk_count` int DEFAULT NULL COMMENT '切片数量，文档切分后的总片段数',
  `permission_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '权限级别：CREATOR（仅创建者）/ DEPT（同部门）/ ALL（全员可查看）',
  `scan_result` json DEFAULT NULL COMMENT '安全扫描结果，JSON字符串，记录敏感词、合规性等扫描结论',
  `uploaded_time` datetime DEFAULT NULL COMMENT '上传时间，文档首次上传完成时写入',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_doc_oss` (`tenant_id`,`kb_id`,`oss_key`),
  KEY `idx_kb_doc_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档实体';
DROP TABLE IF EXISTS `res_kb_document_chunk`;
DROP TABLE IF EXISTS `res_kb_document_chunk`;
CREATE TABLE `res_kb_document_chunk` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库ID，关联res_knowledge_base.id',
  `doc_id` bigint NOT NULL COMMENT '所属文档ID，关联res_kb_document.id',
  `chunk_index` int NOT NULL COMMENT '切片序号，从0开始，用于排序和溯源',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '切片文本内容',
  `token_count` int DEFAULT NULL COMMENT '切片Token数量（估算值）',
  `char_count` int DEFAULT NULL COMMENT '切片字符数',
  `metadata` json DEFAULT NULL COMMENT '切片元数据，JSON格式，包含chunkStrategy、chunkSize、chunkOverlap等切片参数快照',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_chunk_doc_index` (`tenant_id`,`doc_id`,`chunk_index`),
  KEY `idx_kb_chunk_kb_id` (`kb_id`),
  KEY `idx_kb_chunk_doc_id` (`doc_id`),
  FULLTEXT KEY `ft_content` (`content`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档切片表，存储每个文档的切片内容用于预览和检索溯源';
DROP TABLE IF EXISTS `res_kb_process_progress`;
DROP TABLE IF EXISTS `res_kb_process_progress`;
CREATE TABLE `res_kb_process_progress` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库ID',
  `doc_id` bigint NOT NULL COMMENT '所属文档ID，关联res_kb_document.id',
  `step` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '处理步骤：DOWNLOADING（下载中）/ SCANNING（扫描中）/ CHUNKING（切片中）/ EMBEDDING（嵌入中）/ VECTORING（向量入库中）/ COMPLETED（已完成）/ FAILED（失败）',
  `step_order` int NOT NULL COMMENT '步骤顺序，从1开始，用于排序',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '步骤状态：PENDING（待执行）/ RUNNING（执行中）/ COMPLETED（已完成）/ FAILED（失败）',
  `progress_percent` int DEFAULT NULL COMMENT '当前步骤进度百分比，0-100',
  `message` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '步骤描述信息，如"下载MinIO文件..."、"切片 3/10"等',
  `started_at` datetime DEFAULT NULL COMMENT '步骤开始时间',
  `completed_at` datetime DEFAULT NULL COMMENT '步骤完成时间',
  `error_detail` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误详情，失败时记录异常信息',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_kb_progress_doc_id` (`tenant_id`,`doc_id`),
  KEY `idx_kb_progress_kb_id` (`kb_id`),
  KEY `idx_kb_progress_step_order` (`tenant_id`,`doc_id`,`step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档处理进度日志表，记录每个步骤的执行状态用于实时进度推送';
DROP TABLE IF EXISTS `res_kb_subscription`;
DROP TABLE IF EXISTS `res_kb_subscription`;
CREATE TABLE `res_kb_subscription` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `kb_id` bigint NOT NULL,
  `kb_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `subscriber_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `subscriber_id` bigint NOT NULL,
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `deleted` tinyint DEFAULT '0',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT NULL COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_sub` (`tenant_id`,`kb_id`,`subscriber_type`,`subscriber_id`),
  KEY `idx_kb_subscriber` (`tenant_id`,`subscriber_type`,`subscriber_id`),
  KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库订阅关系';
DROP TABLE IF EXISTS `res_knowledge_base`;
DROP TABLE IF EXISTS `res_knowledge_base`;
CREATE TABLE `res_knowledge_base` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `kb_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库唯一编码，租户内唯一，由字母、数字、下划线组成，长度不超过64',
  `kb_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库展示名称，长度不超过128',
  `icon` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库图标URL，可选',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '知识库描述，长度不超过512，说明知识库内容范围与适用场景',
  `security_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全等级：L1~L4，影响知识库可见范围与检索权限',
  `life_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生命周期状态：DRAFT→REVIEWING→PUBLISHED→ARCHIVED',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本号，语义化版本如1.0.0',
  `author_user_id` bigint DEFAULT NULL COMMENT '创建者用户ID，关联org_user.id',
  `author_dept_id` bigint DEFAULT NULL COMMENT '创建者部门ID，关联org_department.id',
  `doc_count` int DEFAULT NULL COMMENT '文档数量，知识库下有效文档总数，由系统自动统计',
  `chunk_strategy` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '切片策略，取值：FIXED（固定长度）/ SENTENCE（按句）/ PARAGRAPH（按段）/ MARKDOWN（按标题）',
  `chunk_size` int DEFAULT NULL COMMENT '切片大小，每段最大字符数，取值范围100-4000，默认500',
  `chunk_overlap` int DEFAULT NULL COMMENT '切片重叠量，相邻切片重叠字符数，取值范围0-500，默认50',
  `embedding_model` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '嵌入模型标识，如doubao-embedding-vision、bge-large-zh、text-embedding-3-large，取值需为模型管理中已启用的EMBEDDING模型',
  `retrieval_strategy` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'VECTOR' COMMENT '检索策略，取值：VECTOR（向量检索）/ KEYWORD（关键词）/ HYBRID（混合检索）',
  `top_k` int DEFAULT NULL COMMENT 'Top-K值，检索返回的最大切片数，取值范围1-20，默认5',
  `similarity_threshold` decimal(20,4) DEFAULT NULL COMMENT '相似度阈值，0-1之间，低于阈值的切片将被过滤；应用层默认0.40（COSINE量纲因嵌入模型而异：doubao系列建议0.40，BGE系列建议0.75）',
  `enable_rerank` tinyint(1) DEFAULT NULL COMMENT '是否启用Rerank重排序，true时对初步检索结果二次排序提升相关性',
  `enable_query_rewrite` tinyint(1) DEFAULT NULL COMMENT '是否启用查询改写，true时对用户原始查询进行扩展或改写以提升召回率',
  `subs_count` int DEFAULT NULL COMMENT '订阅数，该知识库被其他智能体订阅的总次数',
  `published_time` datetime DEFAULT NULL COMMENT '最近发布时间，知识库从草稿转为已发布时写入',
  `visibility` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'TENANT' COMMENT '发布可见范围：TENANT（本租户可见，默认）/ PUBLIC（全平台可见，跨租户市场）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_code` (`tenant_id`,`kb_code`),
  KEY `idx_kb_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库实体';
DROP TABLE IF EXISTS `res_mcp_service`;
DROP TABLE IF EXISTS `res_mcp_service`;
CREATE TABLE `res_mcp_service` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '0=平台公共 >0=租户私有',
  `mcp_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MCP服务唯一编码，全局唯一，由字母、数字、下划线组成，长度不超过64',
  `mcp_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MCP服务展示名称，长度不超过128',
  `icon` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '服务图标URL，可选',
  `provider` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '服务提供方，如"官方"、"合作厂商"，标识服务来源',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '服务描述，长度不超过512，说明服务能力与适用场景',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '版本号，语义化版本如1.0.0',
  `endpoint` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '服务接入端点，SSE/HTTP协议为URL，STDIO协议为可执行命令路径',
  `protocol` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '传输协议：STDIO（标准输入输出）/ SSE（Server-Sent Events）/ STREAMABLE_HTTP（可流式HTTP）',
  `auth_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '鉴权类型：API_KEY/BEARER/OAUTH2/BASIC/NONE，决定authConfig结构',
  `auth_config` json DEFAULT NULL COMMENT '鉴权配置，JSON字符串，依据authType不同结构不同',
  `tool_count` int DEFAULT NULL COMMENT '工具数量，该MCP服务暴露的可调用工具总数，由系统自动统计',
  `security_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全等级：L1~L4，影响服务可用范围与调用权限',
  `subs_count` int DEFAULT NULL COMMENT '订阅数，该服务被订阅的总次数',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：ACTIVE（已接入）/ PENDING（待接入），管理员控制服务可用性',
  `life_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生命周期状态：DRAFT（草稿）/ REVIEWING（审核中）/ PUBLISHED（已发布）/ ARCHIVED（已归档）/ REJECTED（已驳回），管理审核状态机',
  `published_time` datetime DEFAULT NULL COMMENT '最近发布时间，服务首次发布或重新发布时写入',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_mcp_code` (`tenant_id`,`mcp_code`),
  KEY `idx_mcp_service_create_time` (`create_time`),
  KEY `idx_mcp_service_life_status` (`life_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP服务实体';
DROP TABLE IF EXISTS `res_mcp_subscription`;
DROP TABLE IF EXISTS `res_mcp_subscription`;
CREATE TABLE `res_mcp_subscription` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `mcp_service_id` bigint NOT NULL COMMENT 'MCP服务ID，关联 res_mcp_service.id',
  `mcp_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MCP服务编码，冗余存储便于查询',
  `subscriber_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订阅者类型：USER（用户）/ AGENT（智能体）',
  `subscriber_id` bigint NOT NULL COMMENT '订阅者ID（用户ID或智能体ID）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_subscriber_service` (`tenant_id`,`subscriber_type`,`subscriber_id`,`mcp_service_id`,`deleted`),
  KEY `idx_mcp_subscription_service` (`mcp_service_id`),
  KEY `idx_mcp_subscription_subscriber` (`subscriber_type`,`subscriber_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP服务订阅关系表';
DROP TABLE IF EXISTS `res_review`;
DROP TABLE IF EXISTS `res_review`;
CREATE TABLE `res_review` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `resource_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源类型，标识被审核资源种类',
  `resource_id` bigint DEFAULT NULL COMMENT '资源ID，关联对应资源表的主键',
  `resource_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源名称，冗余存储便于审核列表展示',
  `resource_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '资源版本号，标识被审核的版本',
  `applicant_user_id` bigint DEFAULT NULL COMMENT '申请人用户ID，关联org_user.id',
  `applicant_dept_id` bigint DEFAULT NULL COMMENT '申请人部门ID，关联org_department.id',
  `security_level` int DEFAULT NULL COMMENT '安全等级，1-4对应L1-L4，资源声明的安全等级，影响审核严格度',
  `scan_result` json DEFAULT NULL COMMENT '安全扫描结果，JSON字符串，记录敏感词、漏洞、合规性等自动扫描结论',
  `dep_check_result` json DEFAULT NULL COMMENT '依赖检查结果，JSON字符串，记录工具依赖、数据依赖等检查结论',
  `review_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审核状态：PENDING（待审核）/ APPROVED（已通过）/ REJECTED（已拒绝）',
  `reviewer_user_id` bigint DEFAULT NULL COMMENT '审核员用户ID，关联org_user.id，执行人工复核的审核员',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间，审核员作出结论的时间',
  `reject_reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '驳回原因，当reviewStatus为REJECTED时填写',
  `submit_time` datetime DEFAULT NULL COMMENT '提交时间，申请人提交审核的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_review` (`tenant_id`,`resource_type`,`resource_id`,`resource_version`),
  KEY `idx_resource_review_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源审核实体';
DROP TABLE IF EXISTS `res_review_node`;
DROP TABLE IF EXISTS `res_review_node`;
CREATE TABLE `res_review_node` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `review_chain_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批链ID（同一链多节点按 node_order 串联）',
  `node_order` int NOT NULL COMMENT '审批节点顺序，从 1 开始',
  `approver_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '审批人类型：ROLE（角色）/ USER（指定人）/ DEPT_LEADER（部门主管）',
  `approver_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人引用：角色编码 / 用户ID / 部门ID',
  `node_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT '节点状态：PENDING / APPROVED / REJECTED / SKIPPED',
  `reviewed_by` bigint DEFAULT NULL COMMENT '实际审批人ID',
  `reviewed_time` datetime DEFAULT NULL COMMENT '审批时间',
  `comment` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批意见',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_chain` (`tenant_id`,`review_chain_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批节点（多级审批链，PUBLIC 强制两级）';
DROP TABLE IF EXISTS `res_session_summary`;
DROP TABLE IF EXISTS `res_session_summary`;
CREATE TABLE `res_session_summary` (
  `id` bigint NOT NULL COMMENT 'snowflake id',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `session_id` varchar(64) NOT NULL COMMENT 'session id',
  `seq_start` int DEFAULT NULL COMMENT 'seq range start inclusive',
  `seq_end` int DEFAULT NULL COMMENT 'seq range end inclusive',
  `summary_text` text COMMENT 'summary text',
  `token_count` int DEFAULT NULL COMMENT 'estimated token count',
  `create_by` bigint DEFAULT NULL COMMENT 'creator id',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  `deleted` tinyint DEFAULT '0' COMMENT 'logical delete flag',
  PRIMARY KEY (`id`),
  KEY `idx_rss_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='session progressive summary';
DROP TABLE IF EXISTS `res_skill`;
DROP TABLE IF EXISTS `res_skill`;
CREATE TABLE `res_skill` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `skill_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能唯一编码，租户内唯一，由字母、数字、下划线组成，长度不超过64',
  `skill_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能展示名称，长度不超过128，用于资源中心与智能体配置页展示',
  `icon` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能图标URL，可选，用于资源中心视觉标识',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '技能描述，长度不超过512，向用户说明技能用途与适用场景',
  `skill_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能类型：ATOMIC（原子技能）/ COMPOSITE（组合技能）',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能分类：数据处理/内容生成/集成对接/计算/检索',
  `tags` json DEFAULT NULL COMMENT '技能标签，JSON数组格式（如 ["推荐","官方"]），用于检索与筛选',
  `security_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全等级：L1~L4，影响技能可用范围与数据访问权限',
  `life_status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '生命周期状态：DRAFT→REVIEWING→PUBLISHED→ARCHIVED',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能版本号，语义化版本如1.0.0，发布后只增不减',
  `author_user_id` bigint DEFAULT NULL COMMENT '创建者用户ID，关联org_user.id',
  `author_dept_id` bigint DEFAULT NULL COMMENT '创建者部门ID，关联org_department.id，用于部门级权限控制',
  `inputs` json DEFAULT NULL COMMENT '输入参数定义，JSON Schema字符串，描述技能入参结构与约束',
  `outputs` json DEFAULT NULL COMMENT '输出参数定义，JSON Schema字符串，描述技能出参结构',
  `binding_tools` json DEFAULT NULL COMMENT '绑定工具列表，JSON数组格式（如 [1,2,3]），关联res_tool.id',
  `mapping_config` json DEFAULT NULL COMMENT '输入输出映射配置，JSON字符串，描述技能入参与工具入参的转换关系',
  `subs_count` int DEFAULT NULL COMMENT '订阅数，该技能被其他智能体订阅的总次数，用于热门排序',
  `published_time` datetime DEFAULT NULL COMMENT '最近发布时间，技能从草稿转为已发布时写入',
  `visibility` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'TENANT' COMMENT '发布可见范围：TENANT（本租户可见，默认）/ PUBLIC（全平台可见，跨租户市场）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  `instructions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '技能方法论正文（SKILL.md body），V2 核心：承载可执行的操作范式，是创建痛点修复的关键列',
  `references_manifest` json DEFAULT NULL COMMENT '引用资源清单，JSON：{ "文件名": "文件内容" }，由运行时注入为 AgentSkill.resources',
  `trigger_examples` json DEFAULT NULL COMMENT '触发示例，JSON 数组，用于市场检索与模型召回',
  `skill_form` json DEFAULT NULL COMMENT '交互式创建表单 Schema（JSON Schema），驱动 SKILL_CREATEOR 落盘元数据',
  `is_system` tinyint(1) DEFAULT '0' COMMENT '是否系统内置技能（如 SKILL_CREATEOR），1=系统 0=用户',
  `certified` tinyint(1) DEFAULT '0' COMMENT '是否官方认证（认证技能在市场优先展示）',
  `active_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前生效版本指针（指针式发布，回滚=改此列），对应 res_skill_version.version',
  `canary_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '灰度版本指针，NULL 表示无灰度',
  `latest_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最新版本号（含草稿态）',
  `health_score` decimal(5,2) DEFAULT '100.00' COMMENT '技能健康分 0-100，由失败率/评分/弃用率推导',
  `last_invoked_at` datetime DEFAULT NULL COMMENT '最近一次被调用时间，用于活跃度与治理',
  `scope` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'LOCAL' COMMENT '作用域：GLOBAL=全局（所有用户自动加载）/ LOCAL=局部（权限过滤）',
  `exec_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'exec config JSON: model tier/temperature/maxTurns/guardrails',
  `canary_percent` int DEFAULT NULL COMMENT 'canary release percent 1-100, NULL/0=no canary',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_code` (`tenant_id`,`skill_code`),
  KEY `idx_skill_create_time` (`create_time`),
  KEY `idx_scope_status` (`scope`,`life_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能资源实体';
DROP TABLE IF EXISTS `res_skill_subscription`;
DROP TABLE IF EXISTS `res_skill_subscription`;
CREATE TABLE `res_skill_subscription` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `skill_id` bigint NOT NULL COMMENT '关联 res_skill.id',
  `skill_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能编码（冗余）',
  `subscriber_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订阅者类型：USER（用户订阅）/ AGENT（智能体订阅）',
  `subscriber_id` bigint NOT NULL COMMENT '订阅者ID：用户ID 或 智能体ID',
  `subscribed_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '锁定订阅版本，NULL 表示跟随 active_version',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT NULL COMMENT 'update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sub` (`tenant_id`,`skill_id`,`subscriber_type`,`subscriber_id`),
  KEY `idx_subscriber` (`tenant_id`,`subscriber_type`,`subscriber_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能订阅关系（真实订阅，USER/AGENT 两类订阅者）';
DROP TABLE IF EXISTS `res_skill_version`;
DROP TABLE IF EXISTS `res_skill_version`;
CREATE TABLE `res_skill_version` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `skill_id` bigint NOT NULL COMMENT '关联 res_skill.id',
  `skill_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能编码（冗余，便于查询）',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '快照版本号，语义化如 1.0.0',
  `skill_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能展示名称（快照）',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '技能描述（快照）',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分类（快照）',
  `tags` json DEFAULT NULL COMMENT '标签（快照）',
  `security_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全等级（快照）',
  `instructions` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '方法论正文（快照，不可变）',
  `references_manifest` json DEFAULT NULL COMMENT '引用资源（快照）',
  `trigger_examples` json DEFAULT NULL COMMENT '触发示例（快照）',
  `inputs` json DEFAULT NULL COMMENT '输入 Schema（快照）',
  `outputs` json DEFAULT NULL COMMENT '输出 Schema（快照）',
  `binding_tools` json DEFAULT NULL COMMENT '绑定工具（快照）',
  `mapping_config` json DEFAULT NULL COMMENT '映射配置（快照）',
  `is_system` tinyint(1) DEFAULT '0' COMMENT '是否系统技能（快照）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除',
  `debug_report` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '调试报告 JSON',
  `security_report` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '安全扫描报告 JSON',
  `package_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '技能压缩包下载地址',
  `quality_score` decimal(3,1) DEFAULT NULL COMMENT '质量评分（0-10）',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT NULL COMMENT 'update time',
  `exec_config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'exec config snapshot JSON',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_version` (`tenant_id`,`skill_code`,`version`),
  KEY `idx_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能版本快照（不可变，指针式发布/回滚/灰度）';
DROP TABLE IF EXISTS `res_tool`;
DROP TABLE IF EXISTS `res_tool`;
CREATE TABLE `res_tool` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '0=平台内置 >0=租户私有',
  `tool_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具唯一编码，全局唯一，由字母、数字、下划线组成，长度不超过64',
  `tool_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具展示名称，长度不超过128',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '工具描述，长度不超过512，说明工具能力与调用场景',
  `tool_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具类型：READONLY/INTERNAL_API/WRITE/EXTERNAL_NETWORK/CODE_EXEC/HIGH_RISK',
  `source_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '来源类型：BUILTIN（平台内置）/ MCP（MCP工具）',
  `mcp_service_id` bigint DEFAULT NULL COMMENT '关联MCP服务ID，当sourceType为MCP时指向mcp_service.id，否则为null',
  `read_only` tinyint(1) DEFAULT NULL COMMENT '是否只读，true表示工具仅查询不修改数据，false表示有写操作',
  `input_schema` json DEFAULT NULL COMMENT '输入参数Schema，JSON Schema字符串，描述工具入参结构、类型与约束',
  `output_schema` json DEFAULT NULL COMMENT '输出参数Schema，JSON Schema字符串，描述工具出参结构',
  `security_level` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全等级：L1~L4，影响工具调用权限与数据访问范围',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：NORMAL（启用）/ DISABLED（禁用），管理员控制工具可用性',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_tool_code` (`tenant_id`,`tool_code`),
  KEY `idx_tool_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具实体';
DROP TABLE IF EXISTS `sbx_base_image`;
DROP TABLE IF EXISTS `sbx_base_image`;
CREATE TABLE `sbx_base_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID（0=系统公共镜像，>0=租户私有镜像）',
  `image_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '镜像编码',
  `image_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '镜像名称（如 python-datascience）',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述（包含哪些包/环境）',
  `registry_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DOCKER_HUB' COMMENT '镜像仓库类型（DOCKER_HUB/HARBOR）',
  `registry` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'docker.io' COMMENT '镜像仓库地址',
  `repository` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '镜像仓库路径（如 library/python）',
  `tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'latest' COMMENT '镜像标签',
  `digest` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '镜像 SHA256 摘要',
  `image_size_mb` int DEFAULT NULL COMMENT '镜像大小（MB）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ENABLED',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_image_code_tag` (`tenant_id`,`image_code`,`tag`),
  KEY `idx_tenant_status` (`tenant_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='基础镜像注册表（Docker Image）';
DROP TABLE IF EXISTS `sbx_instance`;
DROP TABLE IF EXISTS `sbx_instance`;
CREATE TABLE `sbx_instance` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `instance_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实例唯一标识，UUID字符串，用于全链路追踪',
  `pool_id` bigint DEFAULT NULL COMMENT '所属池ID，关联sbx_pool.id',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实例状态：OCCUPIED（占用中）/ IDLE（空闲）/ ABNORMAL（异常）',
  `snapshot_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AgentScope 沙箱快照ID，用于进程重启后恢复沙箱状态',
  `snapshot_oss_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '沙箱快照 OSS 对象键，MinIO 中存储的快照 tar 包路径',
  `snapshot_time` datetime DEFAULT NULL COMMENT '最新快照时间，记录最近一次快照保存的时间点',
  `reuse_count` int DEFAULT '0' COMMENT '复用次数，同一 slot 被复用的次数，超限触发深度回收',
  `isolation_scope` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '隔离作用域：USER / AGENT / GLOBAL / SESSION',
  `slot_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '沙箱槽位键，由 IsolationScope+业务主键合成，决定 slot 复用粒度',
  `agent_scope_session_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AgentScope SandboxManager sessionKey',
  `user_id` bigint DEFAULT NULL COMMENT '占用用户ID，关联org_user.id，记录实例分配给的用户',
  `agent_id` bigint DEFAULT NULL COMMENT '占用智能体ID，关联agent_def.id，记录实例服务的智能体',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '占用会话ID，关联sess_session.session_id，记录实例绑定的会话',
  `pod_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Pod名称，Kubernetes中运行的容器名，由编排系统生成',
  `namespace` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '命名空间，Kubernetes命名空间，实例运行的逻辑隔离空间',
  `cpu_usage` decimal(20,4) DEFAULT NULL COMMENT 'CPU使用率，0-1之间，当前实例CPU占用比例，由监控采集',
  `mem_usage` decimal(20,4) DEFAULT NULL COMMENT '内存使用率，0-1之间，当前实例内存占用比例，由监控采集',
  `start_time` datetime DEFAULT NULL COMMENT '启动时间，实例创建启动的时间',
  `runtime_minutes` int DEFAULT NULL COMMENT '运行时长，单位分钟，实例累计运行时间',
  `allocated_time` datetime DEFAULT NULL COMMENT '分配时间，实例被分配给会话的时间',
  `recycled_time` datetime DEFAULT NULL COMMENT '回收时间，实例被回收释放的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  `version` int NOT NULL DEFAULT '0' COMMENT '版本号（乐观并发控制），每次状态变更递增',
  `last_heartbeat_time` datetime DEFAULT NULL COMMENT '最后心跳时间（探活更新，用于超时回收判定）',
  `initialized` tinyint NOT NULL DEFAULT '0' COMMENT '是否已完成初始化（Pod Running）',
  `base_image_id` bigint DEFAULT NULL COMMENT '创建时使用的基础镜像ID',
  `last_recycle_time` datetime DEFAULT NULL COMMENT '最近回收时间',
  `recycle_strategy` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近回收策略',
  `resource_fingerprint` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'resource fingerprint',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sandbox_instance_id` (`instance_id`),
  KEY `idx_sandbox_instance_create_time` (`create_time`),
  KEY `idx_sandbox_slot_key` (`slot_key`),
  KEY `idx_pool_status_init` (`pool_id`,`status`,`initialized`),
  KEY `idx_status_recycle` (`status`,`last_recycle_time`),
  KEY `idx_pool_status` (`pool_id`,`status`),
  KEY `idx_status_heartbeat` (`status`,`last_heartbeat_time`),
  KEY `idx_sbx_instance_as_session_key` (`agent_scope_session_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱实例实体';
DROP TABLE IF EXISTS `sbx_lease`;
DROP TABLE IF EXISTS `sbx_lease`;
CREATE TABLE `sbx_lease` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `lease_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租约唯一标识，UUID字符串',
  `instance_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '实例ID，关联sbx_instance',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '持有租约的会话ID',
  `slot_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '槽位键',
  `as_session_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AgentScope sessionKey',
  `expire_at` datetime DEFAULT NULL COMMENT '租约过期时间',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租约状态：ACTIVE / EXPIRED / RELEASED',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT 'creator id',
  `create_time` datetime DEFAULT NULL COMMENT 'create time',
  `update_by` bigint DEFAULT NULL COMMENT 'updater id',
  `update_time` datetime DEFAULT NULL COMMENT 'update time',
  `deleted` tinyint DEFAULT '0' COMMENT 'logical delete flag',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lease_id` (`lease_id`),
  KEY `idx_instance_status` (`instance_id`,`status`),
  KEY `idx_slot_status` (`slot_key`,`status`),
  KEY `idx_expire` (`expire_at`),
  KEY `idx_tenant_status` (`tenant_id`,`status`),
  KEY `idx_sbx_lease_as_session_key` (`as_session_key`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱租约表';
DROP TABLE IF EXISTS `sbx_operation_log`;
DROP TABLE IF EXISTS `sbx_operation_log`;
CREATE TABLE `sbx_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `instance_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '沙箱实例ID',
  `pool_id` bigint DEFAULT NULL COMMENT '所属池ID',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID',
  `operation_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型: ALLOCATE/RELEASE/RECLAIM/DESTROY/REPAIR/HEARTBEAT/FORCE_DESTROY',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNTIME' COMMENT '操作来源: RUNTIME/ADMIN/SYSTEM',
  `from_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态变更前',
  `to_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态变更后',
  `user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '操作Agent ID',
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作会话ID',
  `slot_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '槽位键',
  `success` tinyint NOT NULL DEFAULT '1' COMMENT '是否成功: 1=成功 0=失败',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误码（失败时填写）',
  `error_message` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误消息（失败时填写）',
  `detail_json` json DEFAULT NULL COMMENT '操作详情 JSON（配额/配置参数等）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_instance_id` (`instance_id`),
  KEY `idx_operation_type` (`operation_type`,`create_time`),
  KEY `idx_tenant_status` (`tenant_id`,`success`),
  KEY `idx_slot_key` (`slot_key`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2095304550627205122 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱操作审计日志';
DROP TABLE IF EXISTS `sbx_pool`;
DROP TABLE IF EXISTS `sbx_pool`;
CREATE TABLE `sbx_pool` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID（0=系统共享池，>0=租户私有池）',
  `pool_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '池编码',
  `namespace` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'K8s 命名空间名（aegis-sbx-t{tenantId}-{type}）',
  `min_instances` int NOT NULL DEFAULT '1' COMMENT '最小实例数：始终保持的干净IDLE实例数（预热基准）',
  `max_instances` int NOT NULL DEFAULT '5' COMMENT '最大实例数：总实例数上限（缩容阈值）',
  `max_shared_sessions` int NOT NULL DEFAULT '20' COMMENT '单池最大并发session数',
  `idle_timeout_min` int NOT NULL DEFAULT '30' COMMENT '空闲超时（分钟）：IDLE(脏)超过此时间触发回收',
  `base_image_id` bigint DEFAULT NULL COMMENT '基础镜像ID（关联 sbx_base_image）',
  `pool_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '池名称，长度不超过128，如"轻量脚本池"、"强力计算池"',
  `pool_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '沙箱池类型：LIGHT（通用轻量）/ STANDARD（标准执行）/ HEAVY（重型计算）/ ISOLATED（高安全隔离）/ DEBUG（临时调试）',
  `applicable_scene` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用场景，长度不超过512，说明该池适用的智能体场景',
  `network_policy` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '网络策略：ISOLATED（隔离）/ RESTRICTED（限制出站）/ NO_EXTERNAL（禁止外网）',
  `cpu_limit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'CPU限制，单实例最大CPU核数，如0.5、1、2',
  `mem_limit_mb` int DEFAULT NULL COMMENT '内存限制，单位MB，单实例最大内存',
  `disk_limit_gb` int DEFAULT NULL COMMENT '磁盘限制，单位GB，单实例最大磁盘空间',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：ENABLED（启用）/ DISABLED（禁用）/ MAINTAINING（维护中）',
  `last_reconcile_time` datetime DEFAULT NULL COMMENT '上次Reconcile时间（分布式幂等控制）',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sandbox_pool_name` (`pool_name`),
  UNIQUE KEY `uk_tenant_pool_code` (`tenant_id`,`pool_code`),
  KEY `idx_sandbox_pool_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱池实体';
DROP TABLE IF EXISTS `sec_hitl_history`;
DROP TABLE IF EXISTS `sec_hitl_history`;
CREATE TABLE `sec_hitl_history` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `node_id` bigint DEFAULT NULL COMMENT 'HITL节点ID，关联sec_hitl_node.id，触发的审批节点',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联agent_def.id',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID，关联sess_session.session_id，审批所在会话',
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批动作：APPROVE（通过）/ REJECT（拒绝）/ MODIFY（修改）/ TIMEOUT（超时）',
  `operator_user_id` bigint DEFAULT NULL COMMENT '操作人用户ID，关联org_user.id，执行审批的用户',
  `operator_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人姓名，冗余存储便于审计列表展示',
  `detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '操作详情，JSON字符串，记录审批意见、修改内容等',
  `occur_time` datetime DEFAULT NULL COMMENT '发生时间，审批操作实际发生的时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_hitl_history_node` (`tenant_id`,`node_id`),
  KEY `idx_hitl_history_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='HITL历史实体';
DROP TABLE IF EXISTS `sec_hitl_node`;
DROP TABLE IF EXISTS `sec_hitl_node`;
CREATE TABLE `sec_hitl_node` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联agent_def.id，节点所属智能体',
  `node_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '节点名称，长度不超过128，标识审批环节',
  `trigger_condition` json DEFAULT NULL COMMENT '触发条件，JSON字符串，如 {"toolSecurityLevel":">=3","dataSensitivity":"CONFIDENTIAL"}',
  `approver_user_id` bigint DEFAULT NULL COMMENT '审批人用户ID，关联org_user.id，指定具体审批人',
  `approver_role` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批角色，当approverUserId为空时按角色匹配审批人',
  `sla_hours` int DEFAULT NULL COMMENT 'SLA时限，单位小时，审批超时时间，取值范围1-168',
  `timeout_strategy` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '超时策略：AUTO_APPROVE（自动通过）/ AUTO_REJECT（自动驳回）/ ESCALATE（升级处理）',
  `allowed_actions` json DEFAULT NULL COMMENT '允许的审批操作列表，JSON数组，元素为HitlAction枚举名',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用，true生效，false暂停节点',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hitl_node_name` (`tenant_id`,`agent_id`,`node_name`),
  KEY `idx_hitl_node_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='HITL节点实体';
DROP TABLE IF EXISTS `sec_mask_rule`;
DROP TABLE IF EXISTS `sec_mask_rule`;
CREATE TABLE `sec_mask_rule` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `data_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '数据类型：PHONE（手机号）/ ID_CARD（身份证）/ BANK_CARD（银行卡）/ EMAIL（邮箱）/ IP（IP地址）/ CUSTOM（自定义）',
  `regex` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '识别正则，匹配敏感数据的正则表达式',
  `mask_way` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '脱敏方式：MIDDLE4（中间4位*）/ KEEP_HEAD_TAIL（保留首尾）/ KEEP_LAST4（保留后4位）/ ALL（全部替换）/ HASH（哈希脱敏）',
  `example` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '脱敏示例，展示脱敏前后对比，如"138****1234"',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用，true生效，false暂停规则',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mask_rule_unique` (`tenant_id`,`data_type`,`mask_way`,`deleted`),
  KEY `idx_mask_rule_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据脱敏规则实体';
DROP TABLE IF EXISTS `sec_outbound_policy`;
DROP TABLE IF EXISTS `sec_outbound_policy`;
CREATE TABLE `sec_outbound_policy` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `policy_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '策略类型：WHITELIST_DOMAIN（白名单域名）/ BLACKLIST_IP（黑名单IP）',
  `domain` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '域名，控制访问的目标域名，如api.example.com，支持通配符*.example.com',
  `ip_cidr` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'IP CIDR，控制访问的目标IP段，如192.168.1.0/24',
  `port_limit` int DEFAULT NULL COMMENT '端口限制，允许访问的端口号',
  `applicable_scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用范围：ALL（全部）/ AGENT（指定智能体）/ DEPT（指定部门）',
  `scope_config` json DEFAULT NULL COMMENT '范围配置，JSON数组字符串如[1,2,3]，依据applicableScope关联对应对象ID',
  `valid_hours` int DEFAULT NULL COMMENT '有效时长，单位小时，策略自动失效时间，0表示永久有效',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '策略描述，长度不超过512，说明策略目的与适用场景',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用，true生效，false暂停策略',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  KEY `idx_outbound_policy_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出站策略实体';
DROP TABLE IF EXISTS `sec_sensitive_word`;
DROP TABLE IF EXISTS `sec_sensitive_word`;
CREATE TABLE `sec_sensitive_word` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `word` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '敏感词内容，长度不超过128',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '敏感词分类：GENERAL（通用）/ INDUSTRY（行业）/ ENTERPRISE（企业自定义）/ PRIVACY（个人隐私）',
  `match_mode` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '匹配模式：EXACT（精确）/ FUZZY（模糊）/ REGEX（正则）',
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理动作：BLOCK（拦截）/ REPLACE（替换）/ MARK（标记）',
  `replace_text` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '替换文本，当action为REPLACE时使用的替换内容',
  `scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '适用范围：INPUT（用户输入）/ OUTPUT（模型输出）/ TOOL_RESULT（工具返回）',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用，true生效，false暂停敏感词',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sensitive_word` (`tenant_id`,`word`),
  KEY `idx_sensitive_word_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词实体';
DROP TABLE IF EXISTS `sec_tool_policy`;
DROP TABLE IF EXISTS `sec_tool_policy`;
CREATE TABLE `sec_tool_policy` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `tool_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具类型：READONLY/INTERNAL_API/WRITE/EXTERNAL_NETWORK/CODE_EXEC/HIGH_RISK',
  `security_level` int DEFAULT NULL COMMENT '安全等级阈值，1-4对应L1-L4，控制该类型工具允许的最高安全等级',
  `governance_tier_min` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最低生效治理档位',
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理动作：ALLOW（允许）/ APPROVE（需审批）/ REJECT（拒绝）',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '策略描述，长度不超过512，说明策略目的与适用场景',
  `enabled` tinyint(1) DEFAULT NULL COMMENT '是否启用，true生效，false暂停策略',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_policy_tier` (`tenant_id`,`tool_type`,`security_level`,`governance_tier_min`),
  KEY `idx_tool_policy_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具策略实体';
DROP TABLE IF EXISTS `sess_artifact`;
DROP TABLE IF EXISTS `sess_artifact`;
CREATE TABLE `sess_artifact` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `artifact_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务ID (UUID)',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
  `agent_id` bigint NOT NULL COMMENT '智能体ID',
  `msg_seq` int DEFAULT NULL COMMENT '关联的消息序号',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产物名称 (文件名)',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产物类型: docx/pdf/excel/ppt/image/code/url/other',
  `storage_ref` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '存储引用 (MinIO objectKey / 外部URL)',
  `size` bigint DEFAULT NULL COMMENT '文件大小 (字节)',
  `mime_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'MIME类型',
  `preview_meta` json DEFAULT NULL COMMENT '预览元信息 (页数/尺寸/缩略图)',
  `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
  `parent_artifact_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '父产物ID (版本链)',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '描述/备注',
  `tags` json DEFAULT NULL COMMENT '标签数组',
  `archived` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已归档',
  `expire_at` datetime DEFAULT NULL COMMENT '过期时间 (NULL=永久)',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `created_at` datetime DEFAULT NULL COMMENT 'created at',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_artifact_id` (`artifact_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_msg_seq` (`msg_seq`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_type` (`type`),
  KEY `idx_parent_artifact_id` (`parent_artifact_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话产物表';
DROP TABLE IF EXISTS `sess_message`;
DROP TABLE IF EXISTS `sess_message`;
CREATE TABLE `sess_message` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话ID，关联sess_session.session_id，消息所属会话',
  `message_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '消息类型：USER（用户消息）/ ASSISTANT（助手消息）/ TOOL_CALL（工具调用）/ TOOL_RESULT（工具结果）',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '消息内容，文本类型消息的正文，最长32KB',
  `reasoning` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '推理过程，智能体思维链（CoT）内容，可选，用于透明化展示',
  `tool_call_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具调用ID，当messageType为TOOL_CALL时生成，用于关联调用与结果',
  `tool_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具名称，调用的工具标识，关联res_tool.tool_code',
  `tool_params` json DEFAULT NULL COMMENT '工具参数，JSON字符串，调用工具时传入的参数',
  `tool_result` json DEFAULT NULL COMMENT '工具结果，JSON字符串，工具执行返回的结果',
  `kb_refs` json DEFAULT NULL COMMENT '知识库引用，JSON数组字符串，记录检索命中的知识库切片ID列表',
  `artifact_ids` json DEFAULT NULL COMMENT '关联的产物ID列表 (JSON)',
  `token_input` int DEFAULT NULL COMMENT '输入Token数，该消息消耗的输入token，用于成本核算',
  `token_output` int DEFAULT NULL COMMENT '输出Token数，该消息消耗的输出token，用于成本核算',
  `cost_amount` decimal(20,4) DEFAULT NULL COMMENT '费用金额，该消息产生的费用，单位元',
  `latency_ms` int DEFAULT NULL COMMENT '延迟时间，单位毫秒，处理该消息的总耗时，用于性能监控',
  `seq` int DEFAULT NULL COMMENT '消息序号，会话内自增序号，保证消息顺序',
  `events` json DEFAULT NULL COMMENT '聚合事件JSON',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_msg_seq` (`tenant_id`,`session_id`,`seq`),
  UNIQUE KEY `uk_session_seq` (`session_id`,`seq`),
  KEY `idx_session_msg_create_time` (`create_time`),
  KEY `idx_sess_message_tenant_session_seq` (`tenant_id`,`session_id`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息实体';
DROP TABLE IF EXISTS `sess_session`;
DROP TABLE IF EXISTS `sess_session`;
CREATE TABLE `sess_session` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话唯一标识，UUID字符串，用于全链路追踪',
  `agent_state_session_key` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AgentScope TenantSessionKey 标识（tenantId:userId:agentType:agentId:sessionId）',
  `agent_id` bigint DEFAULT NULL COMMENT '智能体ID，关联agent_def.id，会话绑定的智能体',
  `agent_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体类型：UNIVERSAL/APPLICATION/SYSTEM',
  `agent_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '智能体版本，会话创建时的智能体版本号，保证会话可复现',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID，关联org_user.id，会话所有者',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话标题，长度不超过128，默认取首条消息摘要，用户可修改',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话状态：STARTED→THINKING→TOOL_CALLING→OUTPUTTING，异常为EXCEPTION，结束为ENDED',
  `sandbox_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '沙箱实例ID，关联sbx_instance.id，会话绑定的运行时沙箱',
  `message_count` int DEFAULT NULL COMMENT '消息数量，会话内消息总数，由系统自动统计',
  `token_used` bigint DEFAULT NULL COMMENT '已用Token数，会话累计消耗的token总量，用于成本核算',
  `last_active_time` datetime DEFAULT NULL COMMENT '最后活跃时间，会话最近一次交互时间，用于过期判断',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间，会话自动归档时间，超过此时间未活跃则置为EXPIRED',
  `version_snapshot` json DEFAULT NULL COMMENT '版本快照，JSON字符串，记录会话创建时的智能体配置，保证会话可复现',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`tenant_id`,`session_id`),
  KEY `idx_session_create_time` (`create_time`),
  KEY `idx_sess_session_agent_state` (`agent_state_session_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话实体';
DROP TABLE IF EXISTS `ten_quota`;
DROP TABLE IF EXISTS `ten_quota`;
CREATE TABLE `ten_quota` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `max_agents` int DEFAULT NULL COMMENT '智能体数量上限，含通用与应用智能体',
  `max_resources` int DEFAULT NULL COMMENT '资源数量上限，含技能/知识库/MCP客户端等',
  `max_concurrent_sessions` int DEFAULT NULL COMMENT '最大并发会话数，超限新会话排队或拒绝',
  `max_token_per_day` bigint DEFAULT NULL COMMENT '每日Token上限，自然日0点重置，超限熔断',
  `max_token_per_month` bigint DEFAULT NULL COMMENT '每月Token上限，自然月1号重置，与日配额取严约束',
  `max_sandboxes` int DEFAULT NULL COMMENT '沙箱实例数上限，含占用与空闲实例',
  `max_storage_gb` int DEFAULT NULL COMMENT '存储容量上限（GB），含知识库文档/会话历史/文件附件',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_quota_tenant` (`tenant_id`),
  KEY `idx_tenant_quota_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户配额配置实体';
DROP TABLE IF EXISTS `ten_tenant`;
DROP TABLE IF EXISTS `ten_tenant`;
CREATE TABLE `ten_tenant` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户编码，全局唯一，业务可读标识，用于URL与配置引用，创建后不可修改',
  `tenant_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户名称，展示用，可重复，支持修改',
  `tenant_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户类型：HQ（集团总部）/ SUBSIDIARY（子公司）/ DIVISION（事业部），决定默认配额档位',
  `status` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户状态：NORMAL（正常）/ FROZEN（冻结），冻结后拒绝新建会话与资源操作',
  `contact_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户联系人姓名，用于运营对接',
  `contact_phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租户联系人电话，用于运营对接与紧急通知',
  `expire_time` datetime DEFAULT NULL COMMENT '租户有效期截止时间，过期自动停用，null表示长期有效',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注，运营自定义描述信息',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`),
  KEY `idx_tenant_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户实体，平台多租户隔离的根聚合';
DROP TABLE IF EXISTS `ten_usage`;
DROP TABLE IF EXISTS `ten_usage`;
CREATE TABLE `ten_usage` (
  `id` bigint NOT NULL COMMENT '主键ID（雪花ID）',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `agent_count` int DEFAULT NULL COMMENT '当前智能体数量，实时统计',
  `resource_count` int DEFAULT NULL COMMENT '当前资源数量，实时统计',
  `concurrent_session_count` int DEFAULT NULL COMMENT '当前并发会话数，实时统计',
  `token_used_today` bigint DEFAULT NULL COMMENT '今日已用Token数，自然日0点重置',
  `token_used_this_month` bigint DEFAULT NULL COMMENT '本月已用Token数，自然月1号重置',
  `sandbox_used` int DEFAULT NULL COMMENT '当前沙箱占用数，含占用与空闲',
  `storage_used_gb` decimal(20,4) DEFAULT NULL COMMENT '已用存储容量（GB），精确到小数点后两位',
  `stat_date` date DEFAULT NULL COMMENT '统计日期，每日一条快照，格式 yyyy-MM-dd',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_usage_date` (`tenant_id`,`stat_date`),
  KEY `idx_tenant_usage_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户用量统计实体';



-- ===== sec_sandbox_policy: 沙箱命令策略 =====
DROP TABLE IF EXISTS sec_sandbox_policy;
CREATE TABLE sec_sandbox_policy (
  id bigint NOT NULL COMMENT '主键ID（雪花ID）',
  tenant_id bigint NOT NULL COMMENT '租户ID',
  tool_code varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具编码',
  sandbox_execution tinyint(1) DEFAULT NULL COMMENT '沙箱执行决策：1强制进沙箱 / 0明确不进 / NULL未配置走默认',
  description varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '策略描述',
  enabled tinyint(1) DEFAULT '1' COMMENT '是否启用，true生效 false暂停',
  create_by bigint DEFAULT NULL COMMENT '创建人ID',
  create_time datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_by bigint DEFAULT NULL COMMENT '更新人ID',
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted tinyint DEFAULT '0' COMMENT '逻辑删除：0未删除 1已删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox_policy_tool (tenant_id, tool_code),
  KEY idx_sandbox_policy_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='沙箱命令策略实体';

SET FOREIGN_KEY_CHECKS = 1;

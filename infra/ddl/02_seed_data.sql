-- =============================================================================
-- Aegis Platform - 种子数据 (A 类：平台种子/预置配置数据最小集)
-- -----------------------------------------------------------------------------
-- 用途：新部署最小可运行数据集，保证「建库 → 起服务 → 可登录 → 有沙箱
--       → 有安全基线 → 能对话」闭环。
-- 执行：MySQL 容器首次启动时由 /docker-entrypoint-initdb.d 自动执行
--       （依赖 01_schema_init.sql 先建表）；也可手动执行：
--       mysql -uroot -p aegis < 02_seed_data.sql
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
--   - res_mcp_service MCP服务  : mcp-demo 启动时自动上报
--   - 建租户联动 7 角色        : TenantManageService.create() API
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


-- -----------------------------------------------------------------------------
-- 1. 租户 ten_tenant：默认租户 DEFAULT
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `ten_tenant` (`id`, `tenant_code`, `tenant_name`, `tenant_type`, `status`, `contact_name`, `contact_phone`, `expire_time`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,'DEFAULT','默认租户','HQ','NORMAL','系统管理员','13800000000',NULL,NULL,1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 2. 租户配额 ten_quota：DEFAULT 租户默认配额档
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `ten_quota` (`id`, `tenant_id`, `max_agents`, `max_resources`, `max_concurrent_sessions`, `max_token_per_day`, `max_token_per_month`, `max_sandboxes`, `max_storage_gb`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,1000,10000,500,10000000,300000000,200,1000,1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 3. 组织 org_department：根部门
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_department` (`id`, `tenant_id`, `dept_name`, `parent_id`, `dept_path`, `dept_level`, `sort`, `leader_user_id`, `status`, `sync_source`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,'集团总部',0,'/1',1,1,NULL,'NORMAL','MANUAL',1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 4. 角色 org_role：7 个内置角色（与 UserContext/SecurityConfig 对齐）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_role` (`id`, `tenant_id`, `role_code`, `role_name`, `role_type`, `parent_role_id`, `description`, `sort`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,'SUPER_ADMIN','超级管理员','PLATFORM',NULL,'全平台所有权限',1,'NORMAL',1,NOW(),NULL,NOW(),0),(2,1,'ENTERPRISE_ADMIN','企业管理员','PLATFORM',NULL,'组织/权限/安全/监管/沙箱/预算',2,'NORMAL',1,NOW(),NULL,NOW(),0),(3,1,'SECURITY_ADMIN','安全管理员','PLATFORM',NULL,'安全策略/审计日志/运行时监管/合规报表',3,'NORMAL',1,NOW(),NULL,NOW(),0),(4,1,'RESOURCE_ADMIN','资源管理员','PLATFORM',NULL,'SKILL/MCP/知识库/工具审核与发布',4,'NORMAL',1,NOW(),NULL,NOW(),0),(5,1,'AGENT_REVIEWER','智能体审核员','PLATFORM',NULL,'智能体发布审核/订阅审批/API审核',5,'NORMAL',1,NOW(),NULL,NOW(),0),(6,1,'AGENT_CREATOR','智能体创建者','PLATFORM',NULL,'创建智能体/绑定资源/提交审核/发布API',6,'NORMAL',1,NOW(),NULL,NOW(),0),(7,1,'EMPLOYEE','普通员工','PLATFORM',NULL,'使用通用智能体/订阅应用智能体/管理个人会话',7,'NORMAL',1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 5. 权限 org_permission：平台共享权限树（tenant_id=0）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_permission` (`id`, `tenant_id`, `permission_code`, `permission_name`, `permission_type`, `parent_id`, `sort`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (2000000000000001000,0,'system','系统管理','MENU',0,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001001,0,'tenant:view','租户查看','API',2000000000000001000,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001002,0,'tenant:manage','租户管理','API',2000000000000001000,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001003,0,'user:view','用户查看','API',2000000000000001000,3,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001004,0,'user:manage','用户管理','API',2000000000000001000,4,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001005,0,'role:view','角色查看','API',2000000000000001000,5,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001006,0,'role:manage','角色管理','API',2000000000000001000,6,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001007,0,'dept:view','部门查看','API',2000000000000001000,7,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001008,0,'dept:manage','部门管理','API',2000000000000001000,8,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001100,0,'security','安全管控','MENU',0,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001101,0,'security:policy:view','安全策略查看','API',2000000000000001100,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001102,0,'security:policy:manage','安全策略管理','API',2000000000000001100,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001103,0,'security:audit:view','审计日志查看','API',2000000000000001100,3,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001104,0,'security:mask:view','脱敏规则查看','API',2000000000000001100,4,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001105,0,'security:mask:manage','脱敏规则管理','API',2000000000000001100,5,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001106,0,'security:sensitive:view','敏感词查看','API',2000000000000001100,6,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001107,0,'security:sensitive:manage','敏感词管理','API',2000000000000001100,7,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001200,0,'resource','资源管理','MENU',0,3,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001201,0,'agent:view','智能体查看','API',2000000000000001200,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001202,0,'agent:create','智能体创建','API',2000000000000001200,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001203,0,'agent:edit','智能体编辑','API',2000000000000001200,3,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001204,0,'agent:delete','智能体删除','API',2000000000000001200,4,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001205,0,'agent:publish','智能体发布','API',2000000000000001200,5,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001206,0,'agent:review','智能体审核','API',2000000000000001200,6,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001207,0,'skill:view','技能查看','API',2000000000000001200,7,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001208,0,'skill:create','技能创建','API',2000000000000001200,8,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001209,0,'skill:manage','技能管理','API',2000000000000001200,9,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001210,0,'mcp:view','MCP查看','API',2000000000000001200,10,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001211,0,'mcp:manage','MCP管理','API',2000000000000001200,11,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001212,0,'tool:view','工具查看','API',2000000000000001200,12,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001213,0,'tool:manage','工具管理','API',2000000000000001200,13,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001214,0,'kb:view','知识库查看','API',2000000000000001200,14,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001215,0,'kb:create','知识库创建','API',2000000000000001200,15,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001216,0,'kb:manage','知识库管理','API',2000000000000001200,16,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001300,0,'sandbox','沙箱管理','MENU',0,4,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001301,0,'sandbox:view','沙箱查看','API',2000000000000001300,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001302,0,'sandbox:manage','沙箱管理','API',2000000000000001300,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001400,0,'observe','观测中心','MENU',0,5,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001401,0,'observe:view','观测查看','API',2000000000000001400,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001500,0,'model','模型管理','MENU',0,6,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001501,0,'model:view','模型查看','API',2000000000000001500,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001502,0,'model:manage','模型管理','API',2000000000000001500,2,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001700,0,'hitl','审批管理','MENU',0,8,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001701,0,'hitl:view','审批查看','API',2000000000000001700,1,'NORMAL',1,NOW(),NULL,NOW(),0),(2000000000000001702,0,'hitl:approve','审批操作','API',2000000000000001700,2,'NORMAL',1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 6. 角色权限 org_role_permission：三级 RBAC 授权关系
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_role_permission` (`id`, `tenant_id`, `role_id`, `permission_id`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (2000000000100002001,1,1,2000000000000001000,1,NOW(),NULL,NOW(),0),(2000000000100002002,1,1,2000000000000001001,1,NOW(),NULL,NOW(),0),(2000000000100002003,1,1,2000000000000001002,1,NOW(),NULL,NOW(),0),(2000000000100002004,1,1,2000000000000001003,1,NOW(),NULL,NOW(),0),(2000000000100002005,1,1,2000000000000001004,1,NOW(),NULL,NOW(),0),(2000000000100002006,1,1,2000000000000001005,1,NOW(),NULL,NOW(),0),(2000000000100002007,1,1,2000000000000001006,1,NOW(),NULL,NOW(),0),(2000000000100002008,1,1,2000000000000001007,1,NOW(),NULL,NOW(),0),(2000000000100002009,1,1,2000000000000001008,1,NOW(),NULL,NOW(),0),(2000000000100002010,1,1,2000000000000001100,1,NOW(),NULL,NOW(),0),(2000000000100002011,1,1,2000000000000001101,1,NOW(),NULL,NOW(),0),(2000000000100002012,1,1,2000000000000001102,1,NOW(),NULL,NOW(),0),(2000000000100002013,1,1,2000000000000001103,1,NOW(),NULL,NOW(),0),(2000000000100002014,1,1,2000000000000001104,1,NOW(),NULL,NOW(),0),(2000000000100002015,1,1,2000000000000001105,1,NOW(),NULL,NOW(),0),(2000000000100002016,1,1,2000000000000001106,1,NOW(),NULL,NOW(),0),(2000000000100002017,1,1,2000000000000001107,1,NOW(),NULL,NOW(),0),(2000000000100002018,1,1,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002019,1,1,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002020,1,1,2000000000000001202,1,NOW(),NULL,NOW(),0),(2000000000100002021,1,1,2000000000000001203,1,NOW(),NULL,NOW(),0),(2000000000100002022,1,1,2000000000000001204,1,NOW(),NULL,NOW(),0),(2000000000100002023,1,1,2000000000000001205,1,NOW(),NULL,NOW(),0),(2000000000100002024,1,1,2000000000000001206,1,NOW(),NULL,NOW(),0),(2000000000100002025,1,1,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002026,1,1,2000000000000001208,1,NOW(),NULL,NOW(),0),(2000000000100002027,1,1,2000000000000001209,1,NOW(),NULL,NOW(),0),(2000000000100002028,1,1,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002029,1,1,2000000000000001211,1,NOW(),NULL,NOW(),0),(2000000000100002030,1,1,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002031,1,1,2000000000000001213,1,NOW(),NULL,NOW(),0),(2000000000100002032,1,1,2000000000000001214,1,NOW(),NULL,NOW(),0),(2000000000100002033,1,1,2000000000000001215,1,NOW(),NULL,NOW(),0),(2000000000100002034,1,1,2000000000000001216,1,NOW(),NULL,NOW(),0),(2000000000100002035,1,1,2000000000000001300,1,NOW(),NULL,NOW(),0),(2000000000100002036,1,1,2000000000000001301,1,NOW(),NULL,NOW(),0),(2000000000100002037,1,1,2000000000000001302,1,NOW(),NULL,NOW(),0),(2000000000100002038,1,1,2000000000000001400,1,NOW(),NULL,NOW(),0),(2000000000100002039,1,1,2000000000000001401,1,NOW(),NULL,NOW(),0),(2000000000100002040,1,1,2000000000000001500,1,NOW(),NULL,NOW(),0),(2000000000100002041,1,1,2000000000000001501,1,NOW(),NULL,NOW(),0),(2000000000100002042,1,1,2000000000000001502,1,NOW(),NULL,NOW(),0),(2000000000100002043,1,1,2000000000000001700,1,NOW(),NULL,NOW(),0),(2000000000100002044,1,1,2000000000000001701,1,NOW(),NULL,NOW(),0),(2000000000100002045,1,1,2000000000000001702,1,NOW(),NULL,NOW(),0),(2000000000100002101,1,2,2000000000000001000,1,NOW(),NULL,NOW(),0),(2000000000100002102,1,2,2000000000000001003,1,NOW(),NULL,NOW(),0),(2000000000100002103,1,2,2000000000000001004,1,NOW(),NULL,NOW(),0),(2000000000100002104,1,2,2000000000000001005,1,NOW(),NULL,NOW(),0),(2000000000100002105,1,2,2000000000000001006,1,NOW(),NULL,NOW(),0),(2000000000100002106,1,2,2000000000000001007,1,NOW(),NULL,NOW(),0),(2000000000100002107,1,2,2000000000000001008,1,NOW(),NULL,NOW(),0),(2000000000100002108,1,2,2000000000000001100,1,NOW(),NULL,NOW(),0),(2000000000100002109,1,2,2000000000000001103,1,NOW(),NULL,NOW(),0),(2000000000100002110,1,2,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002111,1,2,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002112,1,2,2000000000000001202,1,NOW(),NULL,NOW(),0),(2000000000100002113,1,2,2000000000000001203,1,NOW(),NULL,NOW(),0),(2000000000100002114,1,2,2000000000000001204,1,NOW(),NULL,NOW(),0),(2000000000100002115,1,2,2000000000000001205,1,NOW(),NULL,NOW(),0),(2000000000100002116,1,2,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002117,1,2,2000000000000001208,1,NOW(),NULL,NOW(),0),(2000000000100002118,1,2,2000000000000001209,1,NOW(),NULL,NOW(),0),(2000000000100002119,1,2,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002120,1,2,2000000000000001211,1,NOW(),NULL,NOW(),0),(2000000000100002121,1,2,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002122,1,2,2000000000000001213,1,NOW(),NULL,NOW(),0),(2000000000100002123,1,2,2000000000000001214,1,NOW(),NULL,NOW(),0),(2000000000100002124,1,2,2000000000000001215,1,NOW(),NULL,NOW(),0),(2000000000100002125,1,2,2000000000000001216,1,NOW(),NULL,NOW(),0),(2000000000100002126,1,2,2000000000000001300,1,NOW(),NULL,NOW(),0),(2000000000100002127,1,2,2000000000000001301,1,NOW(),NULL,NOW(),0),(2000000000100002128,1,2,2000000000000001302,1,NOW(),NULL,NOW(),0),(2000000000100002129,1,2,2000000000000001400,1,NOW(),NULL,NOW(),0),(2000000000100002130,1,2,2000000000000001401,1,NOW(),NULL,NOW(),0),(2000000000100002131,1,2,2000000000000001500,1,NOW(),NULL,NOW(),0),(2000000000100002132,1,2,2000000000000001501,1,NOW(),NULL,NOW(),0),(2000000000100002133,1,2,2000000000000001502,1,NOW(),NULL,NOW(),0),(2000000000100002137,1,2,2000000000000001700,1,NOW(),NULL,NOW(),0),(2000000000100002138,1,2,2000000000000001701,1,NOW(),NULL,NOW(),0),(2000000000100002139,1,2,2000000000000001702,1,NOW(),NULL,NOW(),0),(2000000000100002201,1,3,2000000000000001100,1,NOW(),NULL,NOW(),0),(2000000000100002202,1,3,2000000000000001101,1,NOW(),NULL,NOW(),0),(2000000000100002203,1,3,2000000000000001102,1,NOW(),NULL,NOW(),0),(2000000000100002204,1,3,2000000000000001103,1,NOW(),NULL,NOW(),0),(2000000000100002205,1,3,2000000000000001104,1,NOW(),NULL,NOW(),0),(2000000000100002206,1,3,2000000000000001105,1,NOW(),NULL,NOW(),0),(2000000000100002207,1,3,2000000000000001106,1,NOW(),NULL,NOW(),0),(2000000000100002208,1,3,2000000000000001107,1,NOW(),NULL,NOW(),0),(2000000000100002209,1,3,2000000000000001400,1,NOW(),NULL,NOW(),0),(2000000000100002210,1,3,2000000000000001401,1,NOW(),NULL,NOW(),0),(2000000000100002301,1,4,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002302,1,4,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002303,1,4,2000000000000001202,1,NOW(),NULL,NOW(),0),(2000000000100002304,1,4,2000000000000001203,1,NOW(),NULL,NOW(),0),(2000000000100002305,1,4,2000000000000001204,1,NOW(),NULL,NOW(),0),(2000000000100002306,1,4,2000000000000001205,1,NOW(),NULL,NOW(),0),(2000000000100002307,1,4,2000000000000001206,1,NOW(),NULL,NOW(),0),(2000000000100002308,1,4,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002309,1,4,2000000000000001208,1,NOW(),NULL,NOW(),0),(2000000000100002310,1,4,2000000000000001209,1,NOW(),NULL,NOW(),0),(2000000000100002311,1,4,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002312,1,4,2000000000000001211,1,NOW(),NULL,NOW(),0),(2000000000100002313,1,4,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002314,1,4,2000000000000001213,1,NOW(),NULL,NOW(),0),(2000000000100002315,1,4,2000000000000001214,1,NOW(),NULL,NOW(),0),(2000000000100002316,1,4,2000000000000001215,1,NOW(),NULL,NOW(),0),(2000000000100002317,1,4,2000000000000001216,1,NOW(),NULL,NOW(),0),(2000000000100002401,1,5,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002402,1,5,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002403,1,5,2000000000000001206,1,NOW(),NULL,NOW(),0),(2000000000100002404,1,5,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002405,1,5,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002406,1,5,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002407,1,5,2000000000000001214,1,NOW(),NULL,NOW(),0),(2000000000100002501,1,6,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002502,1,6,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002503,1,6,2000000000000001202,1,NOW(),NULL,NOW(),0),(2000000000100002504,1,6,2000000000000001203,1,NOW(),NULL,NOW(),0),(2000000000100002505,1,6,2000000000000001204,1,NOW(),NULL,NOW(),0),(2000000000100002506,1,6,2000000000000001205,1,NOW(),NULL,NOW(),0),(2000000000100002507,1,6,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002508,1,6,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002509,1,6,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002510,1,6,2000000000000001214,1,NOW(),NULL,NOW(),0),(2000000000100002601,1,7,2000000000000001200,1,NOW(),NULL,NOW(),0),(2000000000100002602,1,7,2000000000000001201,1,NOW(),NULL,NOW(),0),(2000000000100002603,1,7,2000000000000001202,1,NOW(),NULL,NOW(),0),(2000000000100002604,1,7,2000000000000001207,1,NOW(),NULL,NOW(),0),(2000000000100002605,1,7,2000000000000001210,1,NOW(),NULL,NOW(),0),(2000000000100002606,1,7,2000000000000001212,1,NOW(),NULL,NOW(),0),(2000000000100002607,1,7,2000000000000001214,1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 7. 管理员 org_user：admin / aegis@123 (BCrypt cost=10，首次登录请修改)
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_user` (`id`, `tenant_id`, `username`, `password`, `real_name`, `emp_no`, `email`, `phone`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `dept_id`, `mfa_enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,'admin','$2a$10$HUp0dT0GPeivBOtBMdooCOdx4cVaAkTrIpI.vp3X8MbWrhNIiuRru','系统管理员','EMP000001','admin@aegis.com','13800000000',NULL,'NORMAL',NULL,NULL,NULL,0,1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 8. 用户角色绑定 org_user_role：admin → SUPER_ADMIN
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `org_user_role` (`id`, `tenant_id`, `user_id`, `role_id`, `resource_id`, `resource_type`, `source`, `expire_time`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,1,1,NULL,NULL,'DIRECT',NULL,1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 11. 内置工具 res_tool：BUILTIN 工具（tenant_id=0 平台内置）
-- -----------------------------------------------------------------------------
-- 权威对齐源：BuiltinToolRiskConfig（与 HarnessAgent 装配时框架实际注册的工具全集一致，
-- 见 runtime 日志 "Toolkit: Registered tool '...'"），共 27 项：
--   Aegis 自建（4）    ：web_search / image_search / generate_file / http_request
--   框架 Shell（1）    ：execute（framework-drive=true 时经 K8s 沙箱 Pod 执行）
--   框架 Filesystem（6）：read_file / write_file / list_files / grep_files / glob_files / edit_file
--   框架 Subagent/Task（7）：agent_spawn / agent_list / agent_send / task_list / task_cancel / task_output / wait_async_results
--   框架 Memory/Session（6）：memory_search / memory_get / memory_save / session_search / session_list / session_history
--   框架 Skill（2）    ：skill_creator / load_skill_through_path
--   MCP 场景兜底（1）  ：browser_use（浏览器 MCP 接入时注册）
--
-- security_level 与 mapToolLevel 语义对齐：needApproval=true → L3（审批）；
-- LOW → L1（放行）；MEDIUM → L2；http_request 保守标 L3（外网访问，运行时按方法动态评估）。
-- 物理清理 + 全量重建保证幂等（uk_tenant_tool_code 唯一，INSERT IGNORE 兜底）。
DELETE FROM res_tool WHERE tenant_id = 0 AND source_type = 'BUILTIN';
INSERT IGNORE INTO res_tool (id, tenant_id, tool_code, tool_name, description, tool_type, source_type, read_only, security_level, status, create_by, create_time, deleted) VALUES
-- ===== Aegis 自建差异化（4） =====
(3101, 0, 'web_search',    '联网搜索', 'SearXNG/Bing 多引擎聚合 + SSRF 防护 + 结构化返回', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3102, 0, 'image_search',  '图片搜索', 'Bing 图片搜索 + Jsoup 结构化解析', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3103, 0, 'generate_file', '生成文件', 'Apache POI 格式化 Word + MinIO 持久化 + sess_artifact 关联', 'WRITE', 'BUILTIN', 0, 'L1', 'NORMAL', 1, NOW(), 0),
(3104, 0, 'http_request',  'HTTP请求', 'Java HttpClient + SSRF 内网拦截；GET 只读放行 / 写方法需审批', 'EXTERNAL_NETWORK', 'BUILTIN', 1, 'L3', 'NORMAL', 1, NOW(), 0),
-- ===== 框架 ShellExecuteTool（1，K8s 沙箱 Pod 内执行） =====
(3110, 0, 'execute', '代码执行', '框架 ShellExecuteTool，shell/python 命令走 K8s 沙箱 Pod，需审批', 'CODE_EXEC', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0),
-- ===== 框架 FilesystemTool 拆分（6，沙箱文件系统） =====
(3111, 0, 'read_file',  '读文件',   '框架 FilesystemTool，读取沙箱工作区文件内容', 'FILE_OPS', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3112, 0, 'write_file', '写文件',   '框架 FilesystemTool，写入沙箱工作区文件，需审批', 'FILE_OPS', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0),
(3113, 0, 'list_files', '文件列表', '框架 FilesystemTool，列出沙箱工作区文件', 'FILE_OPS', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3114, 0, 'grep_files', '文件搜索', '框架 FilesystemTool，沙箱工作区文件内容搜索', 'FILE_OPS', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3115, 0, 'glob_files', '模式匹配', '框架 FilesystemTool，沙箱工作区文件模式搜索', 'FILE_OPS', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3116, 0, 'edit_file',  '编辑文件', '框架 FilesystemTool，编辑沙箱工作区已有文件，需审批', 'FILE_OPS', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0),
-- ===== 框架 Subagent / Task（7） =====
(3120, 0, 'agent_spawn',        '子智能体创建', '框架 AgentSpawnTool，内部子智能体调度', 'AGENT', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3121, 0, 'agent_list',         '子智能体列表', '框架 AgentListTool，列出子智能体', 'AGENT', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3122, 0, 'agent_send',         '子智能体消息', '框架 AgentSendTool，向子智能体发送消息', 'AGENT', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3123, 0, 'task_list',          '任务列表',     '框架 TaskListTool，列出后台任务', 'ASYNC', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3124, 0, 'task_cancel',        '任务取消',     '框架 TaskCancelTool，取消后台任务，需审批', 'ASYNC', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0),
(3125, 0, 'task_output',        '任务输出',     '框架 TaskOutputTool，读取后台任务输出', 'ASYNC', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3126, 0, 'wait_async_results', '异步结果等待', '框架 WaitAsyncResultsTool，等待异步任务结果', 'ASYNC', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
-- ===== 框架 Memory / Session（6） =====
(3130, 0, 'memory_search',  '记忆搜索', '框架 MemorySearchTool，检索历史记忆', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3131, 0, 'memory_get',     '记忆读取', '框架 MemoryGetTool，读取记忆条目', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3132, 0, 'memory_save',    '记忆保存', '框架 MemorySaveTool，保存会话记忆', 'WRITE', 'BUILTIN', 0, 'L1', 'NORMAL', 1, NOW(), 0),
(3133, 0, 'session_search', '会话搜索', '框架 SessionSearchTool，检索历史会话', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3134, 0, 'session_list',   '会话列表', '框架 SessionListTool，列出历史会话', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
(3135, 0, 'session_history', '会话历史', '框架 SessionHistoryTool，读取会话历史', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
-- ===== 框架 Skill（2） =====
(3140, 0, 'skill_creator',            '技能创建', '框架 skill_creator 元技能，创建技能会注册新工具，需审批', 'WRITE', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0),
(3141, 0, 'load_skill_through_path', '技能加载', '框架 LoadSkillThroughPathTool，按路径加载技能', 'READONLY', 'BUILTIN', 1, 'L1', 'NORMAL', 1, NOW(), 0),
-- ===== MCP 场景兜底（1） =====
(3150, 0, 'browser_use', '浏览器操作', '浏览器 MCP 接入时的自动化操作工具，需审批', 'EXTERNAL_NETWORK', 'BUILTIN', 0, 'L3', 'NORMAL', 1, NOW(), 0);


-- -----------------------------------------------------------------------------
-- 12. 审批节点 res_review_node：PUBLIC 发布默认审批链（tenant_id=0 平台级）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `res_review_node` (`id`, `tenant_id`, `review_chain_id`, `node_order`, `approver_type`, `approver_ref`, `node_status`, `reviewed_by`, `reviewed_time`, `comment`, `create_time`, `deleted`) VALUES (2000000000000000101,0,'default_public',1,'ROLE','RESOURCE_ADMIN','PENDING',NULL,NULL,NULL,NOW(),0),(2000000000000000102,0,'default_public',2,'ROLE','TENANT_ADMIN','PENDING',NULL,NULL,NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 9. 沙箱镜像 sbx_base_image：Docker Hub 公开镜像（tenant_id=0 平台共享）
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sbx_base_image` (`id`, `tenant_id`, `image_code`, `image_name`, `description`, `registry_type`, `registry`, `repository`, `tag`, `digest`, `image_size_mb`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,0,'python311-slim','Python 3.11 Slim 沙箱','轻量级 Python 3.11 沙箱镜像，内置 pip + 常用科学计算库，适合代码执行/数据分析场景','DOCKER_HUB','docker.io','library/python','3.11-slim','sha256:d1dd85f317b225394a85e7822923c4e9f3183b13a2fc05c172a940b937d1483a',189,'ENABLED',1,NOW(),NULL,NOW(),0),(2,0,'python39-slim','Python 3.9 Slim 沙箱','兼容 Python 3.9 旧版本的沙箱镜像，适合运行历史项目代码','DOCKER_HUB','docker.io','library/python','3.9-slim',NULL,185,'ENABLED',1,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 10. 沙箱池 sbx_pool：STANDARD 标准执行池
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sbx_pool` (`id`, `tenant_id`, `pool_code`, `namespace`, `min_instances`, `max_instances`, `max_shared_sessions`, `idle_timeout_min`, `base_image_id`, `pool_name`, `pool_type`, `applicable_scene`, `network_policy`, `cpu_limit`, `mem_limit_mb`, `disk_limit_gb`, `status`, `last_reconcile_time`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1,1,'STANDARD','aegis-sbx-t1-standard',1,5,20,15,1,'标准执行池','STANDARD','通用代码执行与文件处理场景','RESTRICTED','1',256,5,'ENABLED',NULL,NULL,NOW(),NULL,NOW(),0);


-- -----------------------------------------------------------------------------
-- 13. 工具策略 sec_tool_policy：工具安全策略
-- -----------------------------------------------------------------------------
-- 决策矩阵 (toolType × securityLevel) → action(ALLOW/APPROVE/REJECT)，
-- 经 AegisPermissionRuleLoader 装载为 AgentScope PermissionEngine 规则。
-- 低风险免审批：READONLY/AGENT/ASYNC L1~L3 全部 ALLOW（只读查询/内部调度）；
-- FILE_OPS L1/L2 ALLOW（沙箱内文件读写），L3 APPROVE（write_file/edit_file 映射 L3 需审批）。
INSERT IGNORE INTO `sec_tool_policy` (`id`, `tenant_id`, `tool_type`, `security_level`, `governance_tier_min`, `action`, `description`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (11001,1,'READONLY',1,NULL,'ALLOW','只读工具 L1：公开级直接放行',1,1,NOW(),1,NOW(),0),(11002,1,'READONLY',2,NULL,'ALLOW','只读工具 L2：内部级直接放行',1,1,NOW(),1,NOW(),0),(11003,1,'READONLY',3,NULL,'ALLOW','只读工具 L3：机密级直接放行',1,1,NOW(),1,NOW(),0),(11004,1,'READONLY',4,NULL,'APPROVE','只读工具 L4：绝密级需审批',1,1,NOW(),1,NOW(),0),(11005,1,'INTERNAL_API',1,NULL,'ALLOW','内部API L1：公开级直接放行',1,1,NOW(),1,NOW(),0),(11006,1,'INTERNAL_API',2,NULL,'ALLOW','内部API L2：内部级直接放行',1,1,NOW(),1,NOW(),0),(11007,1,'INTERNAL_API',3,NULL,'ALLOW','内部API L3：机密级直接放行',1,1,NOW(),1,NOW(),0),(11008,1,'INTERNAL_API',4,NULL,'REJECT','内部API L4：绝密级拒绝',1,1,NOW(),1,NOW(),0),(11009,1,'WRITE',1,NULL,'ALLOW','写入工具 L1：公开级直接放行',1,1,NOW(),1,NOW(),0),(11010,1,'WRITE',2,NULL,'ALLOW','写入工具 L2：内部级直接放行',1,1,NOW(),1,NOW(),0),(11011,1,'WRITE',3,NULL,'APPROVE','写入工具 L3：机密级需审批',1,1,NOW(),1,NOW(),0),(11012,1,'WRITE',4,NULL,'REJECT','写入工具 L4：绝密级拒绝',1,1,NOW(),1,NOW(),0),(11013,1,'EXTERNAL_NETWORK',1,NULL,'ALLOW','外网工具 L1：公开级放行（白名单域名）',1,1,NOW(),1,NOW(),0),(11014,1,'EXTERNAL_NETWORK',2,NULL,'ALLOW','外网工具 L2：内部级放行',1,1,NOW(),1,NOW(),0),(11015,1,'EXTERNAL_NETWORK',3,NULL,'APPROVE','外网工具 L3：机密级需审批',1,1,NOW(),1,NOW(),0),(11016,1,'EXTERNAL_NETWORK',4,NULL,'REJECT','外网工具 L4：绝密级禁止出网',1,1,NOW(),1,NOW(),0),(11017,1,'CODE_EXEC',1,NULL,'ALLOW','代码执行 L1：公开级放行（沙箱隔离）',1,1,NOW(),1,NOW(),0),(11018,1,'CODE_EXEC',2,NULL,'ALLOW','代码执行 L2：内部级放行',1,1,NOW(),1,NOW(),0),(11019,1,'CODE_EXEC',3,NULL,'APPROVE','代码执行 L3：机密级需审批',1,1,NOW(),1,NOW(),0),(11020,1,'CODE_EXEC',4,NULL,'REJECT','代码执行 L4：绝密级禁止',1,1,NOW(),1,NOW(),0),(11021,1,'HIGH_RISK',1,NULL,'ALLOW','高危工具 L1：公开级放行（沙箱隔离）',1,1,NOW(),1,NOW(),0),(11022,1,'HIGH_RISK',2,NULL,'ALLOW','高危工具 L2：内部级放行',1,1,NOW(),1,NOW(),0),(11023,1,'HIGH_RISK',3,NULL,'APPROVE','高危工具 L3：机密级需审批',1,1,NOW(),1,NOW(),0),(11024,1,'HIGH_RISK',4,NULL,'REJECT','高危工具 L4：绝密级禁止',1,1,NOW(),1,NOW(),0);
INSERT IGNORE INTO `sec_tool_policy` (`id`, `tenant_id`, `tool_type`, `security_level`, `governance_tier_min`, `action`, `description`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES
(11025,1,'AGENT',1,NULL,'ALLOW','智能体调度 L1：公开级直接放行（内部调度无外部副作用）',1,1,NOW(),1,NOW(),0),
(11026,1,'AGENT',2,NULL,'ALLOW','智能体调度 L2：内部级直接放行',1,1,NOW(),1,NOW(),0),
(11027,1,'AGENT',3,NULL,'APPROVE','智能体调度 L3：机密级需审批',1,1,NOW(),1,NOW(),0),
(11028,1,'AGENT',4,NULL,'REJECT','智能体调度 L4：绝密级禁止',1,1,NOW(),1,NOW(),0),
(11029,1,'ASYNC',1,NULL,'ALLOW','异步任务 L1：公开级直接放行（内部任务调度）',1,1,NOW(),1,NOW(),0),
(11030,1,'ASYNC',2,NULL,'ALLOW','异步任务 L2：内部级直接放行',1,1,NOW(),1,NOW(),0),
(11031,1,'ASYNC',3,NULL,'APPROVE','异步任务 L3：机密级需审批',1,1,NOW(),1,NOW(),0),
(11032,1,'ASYNC',4,NULL,'REJECT','异步任务 L4：绝密级禁止',1,1,NOW(),1,NOW(),0),
(11033,1,'FILE_OPS',1,NULL,'ALLOW','文件操作 L1：公开级放行（沙箱工作区内）',1,1,NOW(),1,NOW(),0),
(11034,1,'FILE_OPS',2,NULL,'ALLOW','文件操作 L2：内部级放行（沙箱工作区内）',1,1,NOW(),1,NOW(),0),
(11035,1,'FILE_OPS',3,NULL,'APPROVE','文件操作 L3：机密级需审批',1,1,NOW(),1,NOW(),0),
(11036,1,'FILE_OPS',4,NULL,'REJECT','文件操作 L4：绝密级禁止',1,1,NOW(),1,NOW(),0);


-- -----------------------------------------------------------------------------
-- 14. 脱敏规则 sec_mask_rule：内置脱敏规则
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_mask_rule` (`id`, `tenant_id`, `data_type`, `regex`, `mask_way`, `example`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (13001,1,'PHONE','1[3-9]\\d{9}','MIDDLE4','138****5678',1,1,NOW(),1,NOW(),0),(13002,1,'ID_CARD','[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]','KEEP_HEAD_TAIL','110***********1234',1,1,NOW(),1,NOW(),0),(13003,1,'BANK_CARD','62[0-9]{14,17}','KEEP_LAST4','************1234',1,1,NOW(),1,NOW(),0),(13004,1,'EMAIL','[\\w.+-]+@[\\w-]+\\.[\\w.-]+','KEEP_HEAD_TAIL','z***@example.com',1,1,NOW(),1,NOW(),0),(13005,1,'IP','\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}','ALL','***.***.***.***',1,1,NOW(),1,NOW(),0),(13006,1,'PASSPORT','[EeGgPp][0-9]{8}','KEEP_HEAD_TAIL','E******67',1,1,NOW(),1,NOW(),0),(13007,1,'LICENSE','[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5}','KEEP_LAST4','京A12345 → 京****5',1,1,NOW(),1,NOW(),0),(13008,1,'COMPANY_ID','[0-9A-HJ-NPQRTUWXY]{18}','KEEP_HEAD_TAIL','911100***********9X',1,1,NOW(),1,NOW(),0);


-- -----------------------------------------------------------------------------
-- 15. 敏感词 sec_sensitive_word：内置敏感词
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_sensitive_word` (`id`, `tenant_id`, `word`, `category`, `match_mode`, `action`, `replace_text`, `scope`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (12001,1,'诈骗','GENERAL','EXACT','BLOCK',NULL,'INPUT',1,1,NOW(),1,NOW(),0),(12002,1,'赌博','GENERAL','EXACT','BLOCK',NULL,'INPUT',1,1,NOW(),1,NOW(),0),(12003,1,'博彩','GENERAL','EXACT','BLOCK',NULL,'INPUT',1,1,NOW(),1,NOW(),0),(12004,1,'自杀','GENERAL','EXACT','BLOCK',NULL,'ALL',1,1,NOW(),1,NOW(),0),(12005,1,'毒品','GENERAL','EXACT','BLOCK',NULL,'INPUT',1,1,NOW(),1,NOW(),0),(12010,1,'密码','PRIVACY','EXACT','REPLACE','***','ALL',1,1,NOW(),1,NOW(),0),(12011,1,'口令','PRIVACY','EXACT','REPLACE','***','ALL',1,1,NOW(),1,NOW(),0),(12012,1,'密钥','PRIVACY','EXACT','REPLACE','***','ALL',1,1,NOW(),1,NOW(),0),(12013,1,'token','PRIVACY','FUZZY','REPLACE','***','ALL',1,1,NOW(),1,NOW(),0);


-- -----------------------------------------------------------------------------
-- 16. 出境策略 sec_outbound_policy：数据出境策略
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO `sec_outbound_policy` (`id`, `tenant_id`, `policy_type`, `domain`, `ip_cidr`, `port_limit`, `applicable_scope`, `scope_config`, `valid_hours`, `description`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (17001,1,'BLACKLIST_IP',NULL,'127.0.0.0/8',NULL,'ALL',NULL,NULL,'禁止访问回环地址（SSRF 防护）',1,1,NOW(),1,NOW(),0),(17002,1,'BLACKLIST_IP',NULL,'10.0.0.0/8',NULL,'ALL',NULL,NULL,'禁止访问 A 类内网 10.0.0.0/8（SSRF 防护）',1,1,NOW(),1,NOW(),0),(17003,1,'BLACKLIST_IP',NULL,'172.16.0.0/12',NULL,'ALL',NULL,NULL,'禁止访问 B 类内网 172.16.0.0/12（SSRF 防护）',1,1,NOW(),1,NOW(),0),(17004,1,'BLACKLIST_IP',NULL,'192.168.0.0/16',NULL,'ALL',NULL,NULL,'禁止访问 C 类内网 192.168.0.0/16（SSRF 防护）',1,1,NOW(),1,NOW(),0),(17005,1,'BLACKLIST_IP',NULL,'169.254.0.0/16',NULL,'ALL',NULL,NULL,'禁止访问链路本地 169.254.0.0/16（含云元数据服务）',1,1,NOW(),1,NOW(),0),(17006,1,'BLACKLIST_IP',NULL,'0.0.0.0/8',NULL,'ALL',NULL,NULL,'禁止访问当前网络 0.0.0.0/8（SSRF 防护）',1,1,NOW(),1,NOW(),0),(17010,1,'WHITELIST_DOMAIN','api.openai.com',NULL,443,'ALL',NULL,NULL,'允许调用 OpenAI API（HTTPS 443）',1,1,NOW(),1,NOW(),0),(17011,1,'WHITELIST_DOMAIN','*.volces.com',NULL,443,'ALL',NULL,NULL,'允许调用火山引擎（豆包）API（HTTPS 443）',1,1,NOW(),1,NOW(),0),(17012,1,'WHITELIST_DOMAIN','*.aliyuncs.com',NULL,443,'ALL',NULL,NULL,'允许访问阿里云 OSS（HTTPS 443）',1,1,NOW(),1,NOW(),0),(17013,1,'WHITELIST_DOMAIN','*.tencentcloudapi.com',NULL,443,'ALL',NULL,NULL,'允许调用腾讯云 API（HTTPS 443）',1,1,NOW(),1,NOW(),0),(17014,1,'WHITELIST_DOMAIN','*.baidu.com',NULL,443,'ALL',NULL,NULL,'允许访问百度系 API（HTTPS 443）',1,1,NOW(),1,NOW(),0),(17015,1,'WHITELIST_DOMAIN','weixin.qq.com',NULL,443,'ALL',NULL,NULL,'允许访问微信开放平台（HTTPS 443）',1,1,NOW(),1,NOW(),0);


-- -----------------------------------------------------------------------------
-- 17. 沙箱策略 sec_sandbox_policy：系统工具沙箱执行策略（tenant_id=0 全局）
-- -----------------------------------------------------------------------------
-- 与 BuiltinToolRiskConfig.sandboxExecution 精确对齐（27 工具全覆盖）：
--   sandbox_execution=1 → 框架 SandboxLifecycleMiddleware 驱动 K8s 沙箱 Pod
--                         （execute + 6 个文件工具，共 7 项）
--   sandbox_execution=0 → 宿主安全执行（自建工具走 Java 实现 + SSRF 防护；
--                         内部调度/检索无外部副作用，无需沙箱开销）
-- 物理清理：tenant_id=0 全删重建 + 幽灵工具编码兜底清理（保证幂等）。
DELETE FROM sec_sandbox_policy WHERE tenant_id = 0;
DELETE FROM sec_sandbox_policy WHERE tool_code IN ('aegis_execute','aegis_generate_file','shell','run_script','build_test','exec_attachment','filesystem','agent_generate','task_spawn','skill_manage');
INSERT INTO sec_sandbox_policy (id, tenant_id, tool_code, sandbox_execution, description, enabled, create_by, create_time, deleted) VALUES
-- ===== 框架 ShellExecuteTool（强制沙箱） =====
(101, 0, 'execute',     1, '框架 ShellExecuteTool - shell/python 命令走 K8s 沙箱 Pod', 1, 1, NOW(), 0),
-- ===== 框架 FilesystemTool（沙箱文件系统） =====
(102, 0, 'read_file',   1, '框架 FilesystemTool - 读取沙箱工作区文件', 1, 1, NOW(), 0),
(103, 0, 'write_file',  1, '框架 FilesystemTool - 写入沙箱工作区文件', 1, 1, NOW(), 0),
(104, 0, 'list_files',  1, '框架 FilesystemTool - 列出沙箱工作区文件', 1, 1, NOW(), 0),
(105, 0, 'grep_files',  1, '框架 FilesystemTool - 沙箱工作区内容搜索', 1, 1, NOW(), 0),
(106, 0, 'glob_files',  1, '框架 FilesystemTool - 沙箱工作区模式搜索', 1, 1, NOW(), 0),
(107, 0, 'edit_file',   1, '框架 FilesystemTool - 编辑沙箱工作区文件', 1, 1, NOW(), 0),
-- ===== Aegis 自建（宿主安全执行） =====
(201, 0, 'web_search',    0, '自建 - SearXNG/Bing 直连 + SSRF 防护', 1, 1, NOW(), 0),
(202, 0, 'image_search',  0, '自建 - Bing 直连 + SSRF 防护',         1, 1, NOW(), 0),
(203, 0, 'generate_file', 0, '自建 - 宿主 POI 生成 + MinIO 持久化',   1, 1, NOW(), 0),
(204, 0, 'http_request',  0, '自建 - 宿主 HttpClient + SSRF 内网拦截（GET 只读/写方法审批）', 1, 1, NOW(), 0),
-- ===== 框架 Subagent / Task（内部调度，无外部副作用） =====
(301, 0, 'agent_spawn',        0, '框架 AgentSpawnTool - 内部子智能体调度', 1, 1, NOW(), 0),
(302, 0, 'agent_list',         0, '框架 AgentListTool - 列出子智能体',      1, 1, NOW(), 0),
(303, 0, 'agent_send',         0, '框架 AgentSendTool - 子智能体消息',      1, 1, NOW(), 0),
(304, 0, 'task_list',          0, '框架 TaskListTool - 列出后台任务',       1, 1, NOW(), 0),
(305, 0, 'task_cancel',        0, '框架 TaskCancelTool - 取消后台任务',     1, 1, NOW(), 0),
(306, 0, 'task_output',        0, '框架 TaskOutputTool - 读取任务输出',     1, 1, NOW(), 0),
(307, 0, 'wait_async_results', 0, '框架 WaitAsyncResultsTool - 等待异步结果', 1, 1, NOW(), 0),
-- ===== 框架 Memory / Session（内部向量库/会话存储） =====
(401, 0, 'memory_search',   0, '框架 MemorySearchTool - 内部记忆检索', 1, 1, NOW(), 0),
(402, 0, 'memory_get',      0, '框架 MemoryGetTool - 内部记忆读取',    1, 1, NOW(), 0),
(403, 0, 'memory_save',     0, '框架 MemorySaveTool - 内部记忆写入',   1, 1, NOW(), 0),
(404, 0, 'session_search',  0, '框架 SessionSearchTool - 内部会话检索', 1, 1, NOW(), 0),
(405, 0, 'session_list',    0, '框架 SessionListTool - 列出历史会话',   1, 1, NOW(), 0),
(406, 0, 'session_history', 0, '框架 SessionHistoryTool - 读取会话历史', 1, 1, NOW(), 0),
-- ===== 框架 Skill / MCP 兜底 =====
(501, 0, 'skill_creator',           0, '框架 skill_creator - 技能创建编排（需审批）', 1, 1, NOW(), 0),
(502, 0, 'load_skill_through_path', 0, '框架技能加载 - 按路径只读加载',               1, 1, NOW(), 0),
(503, 0, 'browser_use',             0, '浏览器 MCP - 外部浏览器服务执行（需审批）',   1, 1, NOW(), 0);


SET FOREIGN_KEY_CHECKS = 1;

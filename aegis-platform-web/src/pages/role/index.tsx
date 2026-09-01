/**
 * @file 角色权限管理
 * @description 角色列表、新增/编辑（仅资源角色）、删除（仅资源角色）；平台角色只读
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Space,
  Table,
  Tag,
  Tree,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, KeyOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { DataNode } from 'antd/es/tree';
import { PageHeader } from '@/components/common/PageHeader';
import type { Role } from '@/types/organization';
import { getRoleList, createRole, updateRole, deleteRole } from '@/api/role';
import { getPermissionTree, getRolePermissionIds, assignRolePermissions, type Permission } from '@/api/permission';

/** 角色类型 → Tag 配置 */
const ROLE_TYPE_TAG: Record<string, { color: string; text: string }> = {
  PLATFORM: { color: 'purple', text: '平台角色' },
  RESOURCE: { color: 'blue', text: '资源角色' },
};

/** 角色表单值 */
interface RoleFormValues {
  roleCode: string;
  roleName: string;
  description?: string;
  sort?: number;
}

const RolePage: React.FC = () => {
  const { message } = App.useApp();
  const [roleForm] = Form.useForm<RoleFormValues>();

  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(false);

  // 弹窗
  const [modalOpen, setModalOpen] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [editingRoleId, setEditingRoleId] = useState<string | null>(null);

  // 权限分配弹窗
  const [permModalOpen, setPermModalOpen] = useState(false);
  const [permModalLoading, setPermModalLoading] = useState(false);
  const [permRoleId, setPermRoleId] = useState<string | null>(null);
  const [allPermissions, setAllPermissions] = useState<Permission[]>([]);
  const [checkedPermIds, setCheckedPermIds] = useState<string[]>([]);

  /** 拉取角色列表 */
  const fetchRoles = useCallback(() => {
    setLoading(true);
    getRoleList()
      .then((data) => setRoles(data ?? []))
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    fetchRoles();
  }, [fetchRoles]);

  // ===== 权限分配 =====
  /** 将扁平权限列表组装为 Tree DataNode */
  const buildPermTreeData = useCallback((perms: Permission[]): DataNode[] => {
    const map = new Map<string, DataNode & { children?: DataNode[] }>();
    perms.forEach((p) => {
      map.set(p.id, {
        key: p.id,
        title: p.permissionName ? `${p.permissionName} (${p.permissionCode})` : p.permissionCode,
        children: [],
      });
    });
    const roots: DataNode[] = [];
    perms.forEach((p) => {
      const node = map.get(p.id)!;
      const parentKey = p.parentId ? String(p.parentId) : null;
      if (parentKey && map.has(parentKey)) {
        (map.get(parentKey)!.children as DataNode[]).push(node);
      } else {
        roots.push(node);
      }
    });
    // 清理空 children
    const clean = (nodes: DataNode[]) =>
      nodes.forEach((n) => {
        const withChildren = n as DataNode & { children?: DataNode[] };
        if (!withChildren.children || withChildren.children.length === 0) {
          delete withChildren.children;
        } else {
          clean(withChildren.children);
        }
      });
    clean(roots);
    return roots;
  }, []);

  const openPermModal = useCallback(
    (record: Role) => {
      setPermRoleId(record.id);
      setPermModalOpen(true);
      setPermModalLoading(false);
      setCheckedPermIds([]);
      Promise.all([getPermissionTree(), getRolePermissionIds(record.id)])
        .then(([tree, ids]) => {
          setAllPermissions(tree ?? []);
          setCheckedPermIds(ids ?? []);
        })
        .catch(() => message.error('加载权限数据失败'));
    },
    [message],
  );

  const submitPermissions = useCallback(() => {
    if (!permRoleId) return;
    setPermModalLoading(true);
    assignRolePermissions(permRoleId, checkedPermIds)
      .then(() => {
        message.success('权限分配成功');
        setPermModalOpen(false);
      })
      .catch(() => message.error('权限分配失败'))
      .finally(() => setPermModalLoading(false));
  }, [permRoleId, checkedPermIds, message]);

  // ===== 新增 / 编辑 =====
  const openModal = (record?: Role) => {
    roleForm.resetFields();
    if (record) {
      setEditingRoleId(record.id);
      roleForm.setFieldsValue({
        roleCode: record.roleCode,
        roleName: record.roleName,
        description: record.description,
        sort: record.sort ?? 0,
      });
    } else {
      setEditingRoleId(null);
      roleForm.setFieldsValue({ sort: 0 });
    }
    setModalOpen(true);
  };

  const submitRole = async () => {
    try {
      const values = await roleForm.validateFields();
      setModalLoading(true);
      // 新建角色固定为资源角色，平台角色由系统预置
      const payload: Partial<Role> = { ...values, roleType: 'RESOURCE' };
      if (editingRoleId !== null) {
        await updateRole(editingRoleId, payload);
        message.success('角色更新成功');
      } else {
        await createRole(payload);
        message.success('角色新增成功');
      }
      setModalOpen(false);
      fetchRoles();
    } catch (err) {
      console.error(err);
    } finally {
      setModalLoading(false);
    }
  };

  const handleDelete = async (record: Role) => {
    try {
      await deleteRole(record.id);
      message.success(`已删除「${record.roleName}」`);
      fetchRoles();
    } catch {
      /* 弹错已处理 */
    }
  };

  // ===== 列定义 =====
  const columns: ColumnsType<Role> = [
    { title: '角色编码', dataIndex: 'roleCode', width: 160 },
    { title: '角色名称', dataIndex: 'roleName', width: 160 },
    {
      title: '类型',
      dataIndex: 'roleType',
      width: 110,
      render: (t: string) => {
        const cfg = ROLE_TYPE_TAG[t] ?? { color: 'default', text: t };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '描述', dataIndex: 'description' },
    {
      title: '排序',
      dataIndex: 'sort',
      width: 80,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s?: string) => (s === 'DISABLED' ? <Tag>禁用</Tag> : <Tag color="success">启用</Tag>),
    },
    {
      title: '操作',
      width: 220,
      fixed: 'right',
      render: (_: unknown, record: Role) => {
        const isPlatform = record.roleType === 'PLATFORM';
        return (
          <Space size="small">
            <a onClick={() => openPermModal(record)} style={{ cursor: 'pointer' }}>
              <KeyOutlined /> 权限
            </a>
            <a
              onClick={() => (isPlatform ? message.warning('平台角色不可编辑') : openModal(record))}
              style={{ color: isPlatform ? '#d1d5db' : undefined, cursor: isPlatform ? 'not-allowed' : 'pointer' }}
            >
              <EditOutlined /> 编辑
            </a>
            {isPlatform ? (
              <span style={{ color: '#d1d5db', cursor: 'not-allowed' }}>
                <DeleteOutlined /> 删除
              </span>
            ) : (
              <Popconfirm title="确认删除该角色？" onConfirm={() => handleDelete(record)}>
                <a style={{ color: '#ef4444' }}>
                  <DeleteOutlined /> 删除
                </a>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <PageHeader title="角色权限" desc="平台角色只读 · 资源角色可新增/编辑/删除" />
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
          <span style={{ color: '#9ca3af', fontSize: 13 }}>
            平台角色由系统预置，不可修改；资源角色可按业务需要自定义。
          </span>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={fetchRoles}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>
              新增资源角色
            </Button>
          </Space>
        </div>
        <Table<Role>
          rowKey="id"
          columns={columns}
          dataSource={roles}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={false}
        />
      </Card>

      {/* 新增 / 编辑角色弹窗 */}
      <Modal
        title={editingRoleId !== null ? '编辑角色' : '新增资源角色'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={submitRole}
        confirmLoading={modalLoading}
        width={640}
        destroyOnClose
      >
        <Form<RoleFormValues> form={roleForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="roleCode" label="角色编码" rules={[{ required: true, message: '请输入角色编码' }]}>
                <Input placeholder="如 RESOURCE_VIEWER" disabled={editingRoleId !== null} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="roleName" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
                <Input placeholder="如 资源查看者" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="sort" label="排序">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="description" label="描述">
                <Input.TextArea rows={3} placeholder="角色职责说明" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      {/* 权限分配弹窗 */}
      <Modal
        title="分配权限"
        open={permModalOpen}
        onCancel={() => setPermModalOpen(false)}
        onOk={submitPermissions}
        confirmLoading={permModalLoading}
        width={520}
        destroyOnClose
      >
        <Tree
          checkable
          defaultExpandAll
          treeData={buildPermTreeData(allPermissions)}
          checkedKeys={checkedPermIds}
          onCheck={(keys) => setCheckedPermIds((Array.isArray(keys) ? keys : (keys as { checked: React.Key[] }).checked) as string[])}
        />
      </Modal>
    </div>
  );
};

export default RolePage;

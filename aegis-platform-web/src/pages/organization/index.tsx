/**
 * @file 组织架构管理
 * @description 左侧部门树（新增/编辑/删除）+ 右侧用户列表（新增/编辑/分配角色/禁用启用）
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Dropdown,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tree,
  TreeSelect,
} from 'antd';
import type { MenuProps } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { DataNode } from 'antd/es/tree';
import {
  ApartmentOutlined,
  EditOutlined,
  EllipsisOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { PageResult } from '@/api/types';
import { PageHeader } from '@/components/common/PageHeader';
import type { Department, Role, User } from '@/types/organization';
import {
  getDepartmentTree,
  createDepartment,
  updateDepartment,
  deleteDepartment,
} from '@/api/organization';
import {
  getUserPage,
  createUser,
  updateUser,
  assignUserRoles,
  disableUser,
  enableUser,
} from '@/api/user';
import { getRoleList } from '@/api/role';

/** 用户状态 → Tag 配置 */
const USER_STATUS_TAG: Record<string, { color: string; text: string }> = {
  NORMAL: { color: 'success', text: '正常' },
  DISABLED: { color: 'default', text: '禁用' },
};

/** TreeSelect 节点数据 */
interface TreeSelectNode {
  value: string;
  title: string;
  children?: TreeSelectNode[];
}

/** 部门表单值 */
interface DeptFormValues {
  deptName: string;
  parentId?: string | null;
  sort?: number;
}

/** 用户表单值 */
interface UserFormValues {
  username: string;
  password?: string;
  realName?: string;
  empNo?: string;
  email?: string;
  phone?: string;
  deptId?: string;
  roleIds?: string[];
}

const OrganizationPage: React.FC = () => {
  const { message } = App.useApp();
  const [deptForm] = Form.useForm<DeptFormValues>();
  const [userForm] = Form.useForm<UserFormValues>();

  // 部门树
  const [departments, setDepartments] = useState<Department[]>([]);
  const [deptLoading, setDeptLoading] = useState(false);
  const [selectedDeptId, setSelectedDeptId] = useState<string | undefined>(undefined);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);

  // 部门弹窗
  const [deptModalOpen, setDeptModalOpen] = useState(false);
  const [deptModalLoading, setDeptModalLoading] = useState(false);
  const [editingDeptId, setEditingDeptId] = useState<string | null>(null);

  // 用户列表
  const [users, setUsers] = useState<User[]>([]);
  const [userTotal, setUserTotal] = useState(0);
  const [userLoading, setUserLoading] = useState(false);
  const [userKeyword, setUserKeyword] = useState('');
  const [userPage, setUserPage] = useState(1);
  const [userPageSize, setUserPageSize] = useState(10);

  // 用户弹窗
  const [userModalOpen, setUserModalOpen] = useState(false);
  const [userModalLoading, setUserModalLoading] = useState(false);
  const [editingUserId, setEditingUserId] = useState<string | null>(null);

  // 分配角色弹窗
  const [roleModalOpen, setRoleModalOpen] = useState(false);
  const [roleModalLoading, setRoleModalLoading] = useState(false);
  const [roleAssignUserId, setRoleAssignUserId] = useState<string | null>(null);
  const [roleAssignName, setRoleAssignName] = useState('');
  const [selectedRoleIds, setSelectedRoleIds] = useState<string[]>([]);

  // 角色列表（供分配）
  const [roles, setRoles] = useState<Role[]>([]);

  /** 部门列表转 AntD Tree 数据 */
  const buildTreeData = useCallback((depts: Department[]): DataNode[] => {
    return depts.map((d) => ({
      key: d.id,
      title: d.deptName,
      children: d.children?.length ? buildTreeData(d.children) : undefined,
    }));
  }, []);

  /** 部门列表转 TreeSelect 数据 */
  const buildTreeSelectData = useCallback(
    (depts: Department[]): TreeSelectNode[] =>
      depts.map((d) => ({
        value: d.id,
        title: d.deptName,
        children: d.children?.length ? buildTreeSelectData(d.children) : undefined,
      })),
    [],
  );

  /** 收集所有部门 ID（用于默认展开） */
  const collectKeys = useCallback((depts: Department[]): string[] => {
    const keys: string[] = [];
    depts.forEach((d) => {
      keys.push(d.id);
      if (d.children?.length) keys.push(...collectKeys(d.children));
    });
    return keys;
  }, []);

  /** 拉取部门树 */
  const fetchDepartments = useCallback(() => {
    setDeptLoading(true);
    getDepartmentTree()
      .then((data) => {
        setDepartments(data ?? []);
        setExpandedKeys(collectKeys(data ?? []));
      })
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setDeptLoading(false));
  }, [collectKeys]);

  /** 拉取用户列表 */
  const fetchUsers = useCallback(() => {
    setUserLoading(true);
    getUserPage({
      keyword: userKeyword || undefined,
      deptId: selectedDeptId,
      page: userPage,
      size: userPageSize,
    })
      .then((res) => {
        const data = res as PageResult<User> & { records?: User[] };
        const list = data.list ?? data.records ?? [];
        setUsers(list);
        setUserTotal(data.total ?? list.length);
      })
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setUserLoading(false));
  }, [userKeyword, selectedDeptId, userPage, userPageSize]);

  useEffect(() => {
    fetchDepartments();
    getRoleList()
      .then(setRoles)
      .catch(() => {
        /* 弹错已处理 */
      });
  }, [fetchDepartments]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  /** 角色选项 */
  const roleOptions = useMemo(
    () => roles.map((r) => ({ value: r.id, label: r.roleName })),
    [roles],
  );

  // ===== 部门操作 =====
  const openDeptModal = (record?: Department, parentId?: string | null) => {
    deptForm.resetFields();
    if (record) {
      setEditingDeptId(record.id);
      deptForm.setFieldsValue({
        deptName: record.deptName,
        parentId: record.parentId ?? undefined,
        sort: record.sort ?? 0,
      });
    } else {
      setEditingDeptId(null);
      deptForm.setFieldsValue({ parentId: parentId ?? undefined, sort: 0 });
    }
    setDeptModalOpen(true);
  };

  const submitDept = async () => {
    try {
      const values = await deptForm.validateFields();
      setDeptModalLoading(true);
      if (editingDeptId !== null) {
        await updateDepartment(editingDeptId, values);
        message.success('部门更新成功');
      } else {
        await createDepartment(values);
        message.success('部门新增成功');
      }
      setDeptModalOpen(false);
      fetchDepartments();
    } catch (err) {
      console.error(err);
    } finally {
      setDeptModalLoading(false);
    }
  };

  const handleDeleteDept = async (record: Department) => {
    try {
      await deleteDepartment(record.id);
      message.success(`已删除「${record.deptName}」`);
      if (selectedDeptId === record.id) setSelectedDeptId(undefined);
      fetchDepartments();
    } catch {
      /* 弹错已处理 */
    }
  };

  /** 部门节点操作菜单 */
  const buildDeptMenu = (record: Department): MenuProps['items'] => [
    {
      key: 'edit',
      label: '编辑',
      onClick: () => openDeptModal(record),
    },
    {
      key: 'add-child',
      label: '新增子部门',
      onClick: () => openDeptModal(undefined, record.id),
    },
    { type: 'divider' },
    {
      key: 'delete',
      label: '删除',
      danger: true,
      onClick: () => handleDeleteDept(record),
    },
  ];

  /** 自定义节点标题 */
  const renderDeptTitle = (node: DataNode) => {
    const dept = departments.find((d) => d.id === node.key);
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingRight: 4 }}>
        <span>{node.title as React.ReactNode}</span>
        <Dropdown menu={{ items: dept ? buildDeptMenu(dept) : [] }} trigger={['click']}>
          <Button type="text" size="small" icon={<EllipsisOutlined />} onClick={(e) => e.stopPropagation()} />
        </Dropdown>
      </div>
    );
  };

  // ===== 用户操作 =====
  const openUserModal = (record?: User) => {
    userForm.resetFields();
    if (record) {
      setEditingUserId(record.id);
      userForm.setFieldsValue({
        username: record.username,
        realName: record.realName,
        empNo: record.empNo,
        email: record.email,
        phone: record.phone,
        deptId: record.deptId ?? selectedDeptId,
        roleIds: record.roles?.map((r) => r.id) ?? [],
      });
    } else {
      setEditingUserId(null);
      userForm.setFieldsValue({ deptId: selectedDeptId });
    }
    setUserModalOpen(true);
  };

  const submitUser = async () => {
    try {
      const values = await userForm.validateFields();
      setUserModalLoading(true);
      const { roleIds, password, ...rest } = values;
      if (editingUserId !== null) {
        await updateUser(editingUserId, rest);
        if (roleIds?.length) {
          await assignUserRoles(editingUserId, roleIds);
        }
        message.success('用户更新成功');
      } else {
        const payload = { ...rest, password };
        const created = await createUser(payload);
        if (roleIds?.length) {
          await assignUserRoles(created.id, roleIds);
        }
        message.success('用户新增成功');
      }
      setUserModalOpen(false);
      fetchUsers();
    } catch (err) {
      console.error(err);
    } finally {
      setUserModalLoading(false);
    }
  };

  const openRoleModal = (record: User) => {
    setRoleAssignUserId(record.id);
    setRoleAssignName(record.realName || record.username);
    setSelectedRoleIds(record.roles?.map((r) => r.id) ?? []);
    setRoleModalOpen(true);
  };

  const submitRoles = async () => {
    if (roleAssignUserId === null) return;
    try {
      setRoleModalLoading(true);
      await assignUserRoles(roleAssignUserId, selectedRoleIds);
      message.success('角色分配成功');
      setRoleModalOpen(false);
      fetchUsers();
    } catch {
      /* 弹错已处理 */
    } finally {
      setRoleModalLoading(false);
    }
  };

  const handleToggleUserStatus = async (record: User) => {
    try {
      if (record.status === 'DISABLED') {
        await enableUser(record.id);
        message.success(`已启用「${record.realName || record.username}」`);
      } else {
        await disableUser(record.id);
        message.success(`已禁用「${record.realName || record.username}」`);
      }
      fetchUsers();
    } catch {
      /* 弹错已处理 */
    }
  };

  // ===== 用户列定义 =====
  const userColumns: ColumnsType<User> = [
    { title: '用户名', dataIndex: 'username', width: 130 },
    { title: '真实姓名', dataIndex: 'realName', width: 120 },
    { title: '工号', dataIndex: 'empNo', width: 110 },
    { title: '邮箱', dataIndex: 'email', width: 180 },
    { title: '手机', dataIndex: 'phone', width: 130 },
    {
      title: '角色',
      dataIndex: 'roles',
      width: 180,
      render: (rs?: Role[]) =>
        rs?.length ? (
          <Space size={4} wrap>
            {rs.map((r) => (
              <Tag key={r.id} color="blue">
                {r.roleName}
              </Tag>
            ))}
          </Space>
        ) : (
          '—'
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s?: string) => {
        const cfg = USER_STATUS_TAG[s ?? 'NORMAL'] ?? { color: 'default', text: s ?? '—' };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '操作',
      width: 220,
      fixed: 'right',
      render: (_: unknown, record: User) => (
        <Space size="small">
          <a onClick={() => openUserModal(record)}>
            <EditOutlined /> 编辑
          </a>
          <a onClick={() => openRoleModal(record)}>
            <TeamOutlined /> 分配角色
          </a>
          {record.status === 'DISABLED' ? (
            <a onClick={() => handleToggleUserStatus(record)} style={{ color: '#10b981' }}>
              启用
            </a>
          ) : (
            <Popconfirm title="确认禁用该用户？" onConfirm={() => handleToggleUserStatus(record)}>
              <a style={{ color: '#ef4444' }}>禁用</a>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="组织架构" desc="部门树管理 · 用户管理 · 角色分配" />
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {/* 左侧：部门树 */}
        <Card
          title={
            <Space>
              <ApartmentOutlined />
              <span>部门树</span>
            </Space>
          }
          size="small"
          style={{ width: 300, flexShrink: 0 }}
          extra={
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openDeptModal()}>
              新增部门
            </Button>
          }
        >
          <Spin spinning={deptLoading}>
            <Tree
              treeData={buildTreeData(departments)}
              titleRender={renderDeptTitle}
              expandedKeys={expandedKeys}
              onExpand={(keys) => setExpandedKeys(keys)}
              selectedKeys={selectedDeptId ? [selectedDeptId] : []}
              onSelect={(keys) => {
                setSelectedDeptId(keys[0] ? String(keys[0]) : undefined);
                setUserPage(1);
              }}
              blockNode
            />
          </Spin>
          {selectedDeptId && (
            <Button
              type="link"
              size="small"
              icon={<ReloadOutlined />}
              onClick={() => {
                setSelectedDeptId(undefined);
                setUserPage(1);
              }}
              style={{ marginTop: 8, padding: 0 }}
            >
              显示全部用户
            </Button>
          )}
        </Card>

        {/* 右侧：用户列表 */}
        <Card style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
            <Space wrap>
              <Input
                placeholder="用户名 / 真实姓名 / 工号"
                value={userKeyword}
                onChange={(e) => setUserKeyword(e.target.value)}
                onPressEnter={() => {
                  setUserPage(1);
                  fetchUsers();
                }}
                style={{ width: 240 }}
                allowClear
              />
              <Button icon={<SearchOutlined />} type="primary" onClick={() => { setUserPage(1); fetchUsers(); }}>
                查询
              </Button>
            </Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openUserModal()}>
              新增用户
            </Button>
          </div>
          <Table<User>
            rowKey="id"
            columns={userColumns}
            dataSource={users}
            loading={userLoading}
            scroll={{ x: 1200 }}
            pagination={{
              current: userPage,
              pageSize: userPageSize,
              total: userTotal,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, s) => {
                setUserPage(p);
                setUserPageSize(s);
              },
            }}
          />
        </Card>
      </div>

      {/* 部门弹窗 */}
      <Modal
        title={editingDeptId !== null ? '编辑部门' : '新增部门'}
        open={deptModalOpen}
        onCancel={() => setDeptModalOpen(false)}
        onOk={submitDept}
        confirmLoading={deptModalLoading}
        width={600}
        destroyOnClose
      >
        <Form<DeptFormValues> form={deptForm} layout="vertical">
          <Form.Item name="deptName" label="部门名称" rules={[{ required: true, message: '请输入部门名称' }]}>
            <Input placeholder="如 研发中心" />
          </Form.Item>
          <Form.Item name="parentId" label="上级部门">
            <TreeSelect
              treeData={buildTreeSelectData(departments)}
              placeholder="不选则为顶级部门"
              allowClear
              treeDefaultExpandAll
            />
          </Form.Item>
          <Form.Item name="sort" label="排序">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 用户弹窗 */}
      <Modal
        title={editingUserId !== null ? '编辑用户' : '新增用户'}
        open={userModalOpen}
        onCancel={() => setUserModalOpen(false)}
        onOk={submitUser}
        confirmLoading={userModalLoading}
        width={760}
        destroyOnClose
      >
        <Form<UserFormValues> form={userForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
                <Input placeholder="登录账号" disabled={editingUserId !== null} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="password"
                label="密码"
                rules={editingUserId === null ? [{ required: true, message: '请输入密码' }] : []}
              >
                <Input.Password placeholder={editingUserId !== null ? '留空则不修改' : '登录密码'} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="realName" label="真实姓名">
                <Input placeholder="真实姓名" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="empNo" label="工号">
                <Input placeholder="工号" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="email" label="邮箱">
                <Input placeholder="邮箱" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="phone" label="手机号">
                <Input placeholder="手机号" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="deptId" label="所属部门">
                <TreeSelect
                  treeData={buildTreeSelectData(departments)}
                  placeholder="选择部门"
                  allowClear
                  treeDefaultExpandAll
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="roleIds" label="角色">
                <Select mode="multiple" options={roleOptions} placeholder="选择角色" allowClear />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      {/* 分配角色弹窗 */}
      <Modal
        title={`分配角色 - ${roleAssignName}`}
        open={roleModalOpen}
        onCancel={() => setRoleModalOpen(false)}
        onOk={submitRoles}
        confirmLoading={roleModalLoading}
        width={600}
        destroyOnClose
      >
        <Checkbox.Group
          value={selectedRoleIds}
          onChange={(values) => setSelectedRoleIds(values as string[])}
          style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
        >
          {roles.map((r) => (
            <Checkbox key={r.id} value={r.id}>
              {r.roleName}（{r.roleCode}）
            </Checkbox>
          ))}
        </Checkbox.Group>
        {roles.length === 0 && <div style={{ color: '#9ca3af', textAlign: 'center' }}>暂无可分配角色</div>}
      </Modal>
    </div>
  );
};

export default OrganizationPage;

/**
 * @file 数据备份 Tab
 * @description 手动备份触发 + 备份历史列表
 *              2026-08 裁剪：备份策略配置为桩功能，已随服务端 /backup/config 端点移除；
 *              备份连接参数改由服务端 aegis.ha.backup.* 配置项管理
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getBackupList, executeBackup } from '@/api/ha';
import type { BackupRecord } from '@/api/ha';
import { COLOR, formatBytes, formatClockDuration } from '../constants';

/** 备份记录 */
type BackupType = 'full' | 'incremental';
type BackupStatus = 'success' | 'running' | 'failed';

interface BackupStatusMeta {
  text: string;
  color: string;
}

const BACKUP_STATUS_MAP: Record<BackupStatus, BackupStatusMeta> = {
  success: { text: '成功', color: COLOR.success },
  running: { text: '进行中', color: COLOR.info },
  failed: { text: '失败', color: COLOR.danger },
};

interface BackupRow {
  key: string;
  backupId: string;
  type: BackupType;
  size: string;
  duration: string;
  status: BackupStatus;
  time: string;
}

const BackupTab: React.FC = () => {
  const { message } = App.useApp();
  const [backupRows, setBackupRows] = useState<BackupRow[]>([]);
  const [backupLoading, setBackupLoading] = useState(false);
  const [executingBackup, setExecutingBackup] = useState(false);

  const fetchBackupList = useCallback(async () => {
    setBackupLoading(true);
    try {
      const data = await getBackupList({ page: 1, size: 20 });
      if (data && typeof data === 'object' && 'records' in data) {
        const records = data.records ?? [];
        const rows: BackupRow[] = records.map((item: BackupRecord, idx: number) => ({
          key: String(item.id ?? item.backupId ?? idx),
          backupId: String(item.backupId ?? ''),
          type: String(item.backupType ?? item.type ?? 'full').toLowerCase() as BackupType,
          size: typeof item.sizeBytes === 'number' ? formatBytes(item.sizeBytes) : String(item.size ?? ''),
          duration:
            typeof item.durationSec === 'number'
              ? formatClockDuration(item.durationSec)
              : String(item.duration ?? ''),
          status: String(item.status ?? 'success') as BackupStatus,
          time: String(item.occurTime ?? item.time ?? item.createdAt ?? ''),
        }));
        setBackupRows(rows);
      }
    } catch {
      message.error('获取备份列表失败');
    } finally {
      setBackupLoading(false);
    }
  }, [message]);

  useEffect(() => {
    fetchBackupList();
  }, [fetchBackupList]);

  const handleExecuteBackup = async () => {
    setExecutingBackup(true);
    try {
      await executeBackup();
      message.success('手动备份已触发');
      fetchBackupList();
    } catch {
      message.error('手动备份触发失败');
    } finally {
      setExecutingBackup(false);
    }
  };

  const backupColumns: ColumnsType<BackupRow> = [
    {
      title: '备份ID',
      dataIndex: 'backupId',
      width: 200,
      render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (t: BackupType) =>
        t?.toLowerCase() === 'full' ? <Tag color={COLOR.primary}>全量</Tag> : <Tag color={COLOR.info}>增量</Tag>,
    },
    { title: '大小', dataIndex: 'size', width: 110 },
    { title: '耗时', dataIndex: 'duration', width: 110 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: BackupStatus) => {
        const item = BACKUP_STATUS_MAP[(status?.toLowerCase() as BackupStatus) ?? 'success'];
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    { title: '时间', dataIndex: 'time', width: 180 },
    {
      title: '操作',
      width: 140,
      render: (_v: unknown, _record: BackupRow) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            disabled
            title="恢复功能规划中，暂未实现"
          >
            恢复
          </Button>
          <Button
            type="link"
            size="small"
            disabled
            title="下载功能规划中，暂未实现"
          >
            下载
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title="手动备份"
        extra={
          <Button type="primary" loading={executingBackup} onClick={handleExecuteBackup}>
            立即执行备份
          </Button>
        }
      >
        <span style={{ color: '#6b7280', fontSize: 13 }}>
          通过 mysqldump 执行全量备份，备份目标与连接参数由服务端 aegis.ha.backup.* 配置项管理。
        </span>
      </Card>
      <Card title="备份历史" style={{ marginTop: 16 }}>
        <Table<BackupRow>
          rowKey="key"
          columns={backupColumns}
          dataSource={backupRows}
          loading={backupLoading}
          pagination={false}
          size="middle"
        />
      </Card>
    </div>
  );
};

export default BackupTab;

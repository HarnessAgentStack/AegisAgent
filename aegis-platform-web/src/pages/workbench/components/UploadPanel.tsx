/**
 * @file 附件上传 Modal
 * @description 从 Workbench 抽取的 Modal 组件，含拖拽/点击上传区、进度条、已选附件列表
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Modal, Button, App } from 'antd';
import { PaperClipOutlined, LoadingOutlined } from '@ant-design/icons';
import { uploadFile } from '@/api/session';
import type { AttachmentRef } from '@/api/session';
import { formatFileSize } from '@/utils/format';

interface UploadPanelProps {
  open: boolean;
  onClose: () => void;
  selected: AttachmentRef[];
  onChange: (list: AttachmentRef[]) => void;
}

export const UploadPanel: React.FC<UploadPanelProps> = ({ open, onClose, selected, onChange }) => {
  const { message } = App.useApp();
  const fileInputRef = React.useRef<HTMLInputElement>(null);
  const [uploadingFile, setUploadingFile] = React.useState<File | null>(null);
  const [uploadProgress, setUploadProgress] = React.useState(0);

  const handleFileSelect = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const file = files[0];
    const maxSize = 20 * 1024 * 1024;
    if (file.size > maxSize) { message.error('文件大小不能超过 20MB'); return; }
    setUploadingFile(file); setUploadProgress(0);
    try {
      const attachment = await uploadFile(file, (progress) => setUploadProgress(progress));
      onChange([...selected, { ...attachment, file }]);
      message.success(`文件上传成功：${file.name}`);
    } catch (error) {
      message.error(`文件上传失败：${(error as Error).message || '请重试'}`);
    } finally {
      setUploadingFile(null); setUploadProgress(0);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <Modal
      title={<div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><PaperClipOutlined style={{ color: '#722ed1' }} /><span>上传附件</span></div>}
      open={open} onCancel={onClose} width={520}
      footer={[
        <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="clear" onClick={() => onChange([])} disabled={selected.length === 0}>清空</Button>,
        <Button key="ok" type="primary" onClick={onClose}>确定 ({selected.length})</Button>,
      ]}
    >
      <input
        ref={fileInputRef} type="file"
        accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.csv,.png,.jpg,.jpeg,.gif,.bmp,.webp"
        style={{ display: 'none' }}
        onChange={(e) => handleFileSelect(e.target.files)}
      />
      <div style={{
        border: '2px dashed #d9d9d9', borderRadius: 8,
        padding: uploadingFile ? '30px 20px' : '40px 20px', textAlign: 'center',
        cursor: uploadingFile ? 'not-allowed' : 'pointer', background: '#fafafa',
        transition: 'all 0.2s', marginBottom: 16,
      }} onClick={() => { if (!uploadingFile) fileInputRef.current?.click(); }}
      >
        {uploadingFile ? (
          <div>
            <LoadingOutlined style={{ fontSize: 32, color: '#722ed1', marginBottom: 12 }} />
            <div style={{ fontSize: 14, color: '#333', marginBottom: 8 }}>正在上传: {uploadingFile.name}</div>
            <div style={{ width: '80%', height: 4, background: '#f0f0f0', borderRadius: 2, margin: '0 auto', overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${uploadProgress}%`, background: '#722ed1', transition: 'width 0.2s' }} />
            </div>
            <div style={{ fontSize: 12, color: '#999', marginTop: 8 }}>{uploadProgress}%</div>
          </div>
        ) : (
          <>
            <PaperClipOutlined style={{ fontSize: 48, color: '#722ed1', marginBottom: 16 }} />
            <div style={{ fontSize: 14, color: '#333', marginBottom: 4 }}>点击选择文件</div>
            <div style={{ fontSize: 12, color: '#999' }}>支持 PDF、Word、Excel、PPT、图片、文本等格式（最大 20MB）</div>
          </>
        )}
      </div>
      {selected.length > 0 && (
        <div>
          <div style={{ fontSize: 13, color: '#666', marginBottom: 8 }}>已选附件：</div>
          <div style={{ maxHeight: 200, overflowY: 'auto' }}>
            {selected.map(att => (
              <div key={att.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: 8, borderRadius: 6, background: '#f5f5f5', marginBottom: 6 }}>
                <PaperClipOutlined style={{ color: '#722ed1' }} />
                <div style={{ flex: 1, overflow: 'hidden' }}>
                  <div style={{ fontSize: 12, color: '#333', whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>{att.fileName}</div>
                  <div style={{ fontSize: 11, color: '#999' }}>{att.fileSize ? formatFileSize(att.fileSize) : ''}</div>
                </div>
                <Button size="small" type="text" danger onClick={() => onChange(selected.filter(a => a.id !== att.id))}>×</Button>
              </div>
            ))}
          </div>
        </div>
      )}
    </Modal>
  );
};

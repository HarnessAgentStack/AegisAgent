package com.aegis.dal.mapper.document;

import com.aegis.core.domain.document.AttFileMeta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 附件文件元数据 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AttFileMetaMapper extends BaseMapper<AttFileMeta> {

    /**
     * 根据 MinIO storageKey 反查 fileId（用于孤儿文件检测）。
     *
     * @param storageKey MinIO object key
     * @return fileId，不存在时返回 null
     */
    @Select("SELECT file_id FROM att_file_meta WHERE storage_key = #{storageKey} AND deleted = 0")
    String findFileIdByStorageKey(@Param("storageKey") String storageKey);
}

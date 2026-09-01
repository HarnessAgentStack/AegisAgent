package com.aegis.dal.mapper.resource;

import com.aegis.core.domain.resource.KbDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 运行时知识库文档 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface RuntimeKbDocumentMapper extends BaseMapper<KbDocument> {
}

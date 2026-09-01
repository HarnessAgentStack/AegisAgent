package com.aegis.runtime.infrastructure.document;

import java.io.IOException;

/**
 * 文件解析器 SPI。
 *
 * <p>每种文件格式对应一个 Parser 实现，通过 {@link #supports} 声明支持的文件类型，
 * 由 {@link FileParseEngine} 根据文件扩展名自动路由到对应 Parser。
 *
 * @author wang.zhen
 */
public interface FileParser {

    /**
     * 是否支持该文件类型。
     *
     * @param extension   文件扩展名（小写，不含点）
     * @param contentType MIME 类型
     * @return true 表示支持
     */
    boolean supports(String extension, String contentType);

    /**
     * 解析文件内容。
     *
     * @param content  文件字节数组
     * @param filename 原始文件名
     * @return 解析结果（含文本 + 元数据）
     * @throws IOException 解析失败时抛出
     */
    ParsedContent parse(byte[] content, String filename) throws IOException;
}

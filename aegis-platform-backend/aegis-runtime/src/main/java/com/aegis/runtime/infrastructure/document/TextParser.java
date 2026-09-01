package com.aegis.runtime.infrastructure.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 纯文本文件解析器。
 *
 * <p>支持 txt/md/csv/json/xml 等纯文本格式，直接按 UTF-8 读取。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class TextParser implements FileParser {

    private static final Set<String> SUPPORTED = Set.of(
            "txt", "md", "csv", "json", "xml");

    @Override
    public boolean supports(String extension, String contentType) {
        return SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public ParsedContent parse(byte[] content, String filename) {
        String text = new String(content, StandardCharsets.UTF_8);
        return ParsedContent.builder()
                .text(text)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .charCount(text.length())
                        .estimatedTokens(text.length() / 4)
                        .build())
                .build();
    }
}

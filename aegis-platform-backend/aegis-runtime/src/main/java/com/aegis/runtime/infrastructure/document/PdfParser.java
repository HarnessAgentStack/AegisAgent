package com.aegis.runtime.infrastructure.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * PDF 文件解析器。
 *
 * <p>使用 Apache PDFBox 提取文本内容 + Tabula-java 提取表格结构。
 * 表格输出为 Markdown 格式，与 HtmlParser/OfficeParser 保持一致。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class PdfParser implements FileParser {

    private static final int MAX_PAGES = 500;
    private static final int MAX_TEXT_LENGTH = 1_000_000;

    @Override
    public boolean supports(String extension, String contentType) {
        return "pdf".equals(extension.toLowerCase())
                || "application/pdf".equals(contentType);
    }

    @Override
    public ParsedContent parse(byte[] content, String filename) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, MAX_PAGES);

            if (totalPages > MAX_PAGES) {
                log.warn("PDF 页数超限，仅提取前 {} 页: filename={}, pages={}",
                        MAX_PAGES, filename, totalPages);
            }

            StringBuilder sb = new StringBuilder();
            int tableCount = 0;

            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            // ObjectExtractor 不使用 try-with-resources：其 close() 会关闭 PDDocument，
            // 由外层 PDDocument 的 try-with-resources 统一管理生命周期
            ObjectExtractor extractor = new ObjectExtractor(document);

            for (int page = 1; page <= pagesToProcess; page++) {
                sb.append("--- 第 ").append(page).append(" 页 ---\n");

                // 1) 提取表格（Tabula）
                try {
                    Page p = extractor.extract(page);
                    List<Table> tables = sea.extract(p);
                    for (Table table : tables) {
                        sb.append(tableToMarkdown(table));
                        sb.append("\n\n");
                        tableCount++;
                    }
                } catch (Exception e) {
                    log.debug("第 {} 页表格提取跳过: {}", page, e.getMessage());
                }

                // 2) 提取文本（PDFBox）
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setLineSeparator("\n");
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isEmpty()) {
                    sb.append(pageText).append("\n");
                }
            }

            String text = sb.toString();
            if (text.length() > MAX_TEXT_LENGTH) {
                log.warn("PDF 文本被截断: filename={}, originalLength={}", filename, text.length());
                text = text.substring(0, MAX_TEXT_LENGTH) + "\n[...truncated...]";
            }

            return ParsedContent.builder()
                    .text(text)
                    .metadata(ParsedContent.ParseMetadata.builder()
                            .pageCount(pagesToProcess)
                            .tableCount(tableCount)
                            .charCount(text.length())
                            .estimatedTokens(text.length() / 4)
                            .build())
                    .build();
        }
    }

    /**
     * Tabula Table → Markdown 表格。
     */
    private String tableToMarkdown(Table table) {
        List<List<technology.tabula.RectangularTextContainer>> rows = table.getRows();
        if (rows.isEmpty()) return "";

        StringBuilder md = new StringBuilder();
        // 表头
        List<technology.tabula.RectangularTextContainer> header = rows.get(0);
        md.append("| ");
        for (technology.tabula.RectangularTextContainer cell : header) {
            md.append(escapeCell(cell.getText())).append(" | ");
        }
        md.append("\n| ");
        for (int i = 0; i < header.size(); i++) {
            md.append("--- | ");
        }
        md.append("\n");

        // 数据行
        for (int r = 1; r < rows.size(); r++) {
            List<technology.tabula.RectangularTextContainer> row = rows.get(r);
            md.append("| ");
            for (technology.tabula.RectangularTextContainer cell : row) {
                md.append(escapeCell(cell.getText())).append(" | ");
            }
            md.append("\n");
        }
        return md.toString();
    }

    private String escapeCell(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\n", " ").trim();
    }
}

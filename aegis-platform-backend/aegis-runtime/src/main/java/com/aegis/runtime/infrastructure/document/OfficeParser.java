package com.aegis.runtime.infrastructure.document;

import com.aegis.core.common.text.TokenEstimator;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Office 文件解析器（DOCX/XLSX/PPTX）。
 *
 * <p>使用 Apache POI 提取结构化内容：
 * <ul>
 *   <li>DOCX: 保留标题层级（→Markdown 标题）、表格（→Markdown 表格）</li>
 *   <li>XLSX: 按 sheet 分块，转为 Markdown 表格</li>
 *   <li>PPTX: 按幻灯片分块，提取文本内容</li>
 * </ul>
 *
 * <h3>安全防护</h3>
 * <p>启用 ZIP bomb 防护（ZipSecureFile），防止恶意 OOXML 文件导致 OOM。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class OfficeParser implements FileParser {

    private static final Set<String> SUPPORTED = Set.of("docx", "xlsx", "pptx");

    @Override
    public boolean supports(String extension, String contentType) {
        return SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public ParsedContent parse(byte[] content, String filename) throws IOException {
        String ext = getExtension(filename).toLowerCase();

        // ZIP bomb 防护
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(100_000_000L);
        ZipSecureFile.setMaxTextSize(50_000_000);

        return switch (ext) {
            case "docx" -> parseDocx(content);
            case "xlsx" -> parseXlsx(content);
            case "pptx" -> parsePptx(content);
            default -> throw new IOException("Unsupported office format: " + ext);
        };
    }

    private ParsedContent parseDocx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        int tableCount = 0;

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            // 提取段落（保留标题层级）
            for (XWPFParagraph para : doc.getParagraphs()) {
                String style = para.getStyle();
                String text = para.getText();
                if (text == null || text.isEmpty()) continue;

                if ("Heading1".equals(style)) {
                    sb.append("# ").append(text).append("\n\n");
                } else if ("Heading2".equals(style)) {
                    sb.append("## ").append(text).append("\n\n");
                } else if ("Heading3".equals(style)) {
                    sb.append("### ").append(text).append("\n\n");
                } else {
                    sb.append(text).append("\n");
                }
            }

            // 提取表格（转为 Markdown 表格）
            for (XWPFTable table : doc.getTables()) {
                sb.append("\n").append(convertTableToMarkdown(table)).append("\n");
                tableCount++;
            }
        }

        String text = sb.toString();
        return ParsedContent.builder()
                .text(text)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .tableCount(tableCount)
                        .charCount(text.length())
                        .estimatedTokens(TokenEstimator.estimateTokens(text))
                        .build())
                .build();
    }

    private ParsedContent parseXlsx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        int sheetCount = 0;
        int tableCount = 0;

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                sheetCount++;

                sb.append(convertSheetToMarkdown(sheet)).append("\n\n");
                tableCount++;
            }
        }

        String text = sb.toString();
        return ParsedContent.builder()
                .text(text)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .sheetCount(sheetCount)
                        .tableCount(tableCount)
                        .charCount(text.length())
                        .estimatedTokens(TokenEstimator.estimateTokens(text))
                        .build())
                .build();
    }

    private ParsedContent parsePptx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        int slideCount = 0;

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(content))) {
            List<XSLFShape> shapes = ppt.getSlides().stream()
                    .flatMap(s -> s.getShapes().stream())
                    .toList();
            slideCount = ppt.getSlides().size();

            for (XSLFShape shape : shapes) {
                if (shape instanceof XSLFTextShape textShape) {
                    for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                        String text = para.getText();
                        if (text != null && !text.isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
            }
        }

        String text = sb.toString();
        return ParsedContent.builder()
                .text(text)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .pageCount(slideCount)
                        .charCount(text.length())
                        .estimatedTokens(TokenEstimator.estimateTokens(text))
                        .build())
                .build();
    }

    private String convertTableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return "";

        // 表头
        XWPFTableRow headerRow = rows.get(0);
        sb.append("| ");
        for (XWPFTableCell cell : headerRow.getTableCells()) {
            sb.append(cell.getText().replace("|", "\\|")).append(" | ");
        }
        sb.append("\n| ");
        for (int i = 0; i < headerRow.getTableCells().size(); i++) {
            sb.append("--- | ");
        }
        sb.append("\n");

        // 数据行
        for (int i = 1; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(cell.getText().replace("|", "\\|")).append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String convertSheetToMarkdown(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<Row> rows = sheet.rowIterator();
        if (!rows.hasNext()) return "";

        // 表头
        Row headerRow = rows.next();
        sb.append("| ");
        for (Cell cell : headerRow) {
            sb.append(getCellValue(cell).replace("|", "\\|")).append(" | ");
        }
        sb.append("\n| ");
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            sb.append("--- | ");
        }
        sb.append("\n");

        // 数据行
        while (rows.hasNext()) {
            Row row = rows.next();
            sb.append("| ");
            for (Cell cell : row) {
                sb.append(getCellValue(cell).replace("|", "\\|")).append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            case BLANK, ERROR, _NONE -> "";
        };
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1) : "";
    }
}

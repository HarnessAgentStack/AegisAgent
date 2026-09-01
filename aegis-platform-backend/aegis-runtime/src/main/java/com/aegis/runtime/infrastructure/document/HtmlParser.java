package com.aegis.runtime.infrastructure.document;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * HTML 文件解析器。
 *
 * <p>将 HTML 转换为 Markdown 格式文本，移除 script/style 等噪声标签，
 * 保留标题、列表、链接、表格等结构化信息。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class HtmlParser implements FileParser {

    @Override
    public boolean supports(String extension, String contentType) {
        return "html".equals(extension.toLowerCase())
                || "htm".equals(extension.toLowerCase())
                || "text/html".equals(contentType);
    }

    @Override
    public ParsedContent parse(byte[] content, String filename) {
        String html = new String(content, StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html);

        // 移除噪声标签
        doc.select("script, style, noscript, iframe, svg, canvas").remove();

        // 移除隐藏元素
        doc.select("[style*='display:none'], [style*='display: none'], [hidden]").remove();

        StringBuilder sb = new StringBuilder();
        convertNode(doc.body() != null ? doc.body() : doc, sb, 0);

        String markdown = sb.toString().trim();
        return ParsedContent.builder()
                .text(markdown)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .charCount(markdown.length())
                        .estimatedTokens(markdown.length() / 4)
                        .build())
                .build();
    }

    private void convertNode(Element node, StringBuilder sb, int depth) {
        String tag = node.tagName().toLowerCase();

        switch (tag) {
            case "h1" -> appendHeading(sb, node.text(), 1);
            case "h2" -> appendHeading(sb, node.text(), 2);
            case "h3" -> appendHeading(sb, node.text(), 3);
            case "h4" -> appendHeading(sb, node.text(), 4);
            case "h5" -> appendHeading(sb, node.text(), 5);
            case "h6" -> appendHeading(sb, node.text(), 6);
            case "p" -> {
                String text = node.text().trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }
            case "br" -> sb.append("\n");
            case "hr" -> sb.append("\n---\n\n");
            case "ul", "ol" -> {
                Elements items = node.select("> li");
                for (int i = 0; i < items.size(); i++) {
                    String prefix = tag.equals("ol") ? (i + 1) + ". " : "- ";
                    sb.append(prefix).append(items.get(i).text().trim()).append("\n");
                }
                sb.append("\n");
            }
            case "blockquote" -> {
                String text = node.text().trim();
                if (!text.isEmpty()) {
                    for (String line : text.split("\n")) {
                        sb.append("> ").append(line).append("\n");
                    }
                    sb.append("\n");
                }
            }
            case "pre" -> {
                Element code = node.selectFirst("code");
                String text = code != null ? code.text() : node.text();
                sb.append("```\n").append(text).append("\n```\n\n");
            }
            case "code" -> {
                String text = node.text();
                if (node.parent() != null && !"pre".equals(node.parent().tagName())) {
                    sb.append("`").append(text).append("`");
                }
            }
            case "table" -> convertTable(node, sb);
            case "img" -> {
                String alt = node.attr("alt");
                String src = node.attr("src");
                if (!alt.isEmpty()) {
                    sb.append("![").append(alt).append("](").append(src).append(")\n");
                }
            }
            case "a" -> {
                String text = node.text();
                String href = node.attr("href");
                if (!text.isEmpty() && !href.isEmpty()) {
                    sb.append("[").append(text).append("](").append(href).append(")");
                }
            }
            case "strong", "b" -> sb.append("**").append(node.text()).append("**");
            case "em", "i" -> sb.append("*").append(node.text()).append("*");
            case "body", "div", "section", "article", "main", "header", "footer", "nav", "aside" -> {
                // 递归处理子节点
                for (Element child : node.children()) {
                    convertNode(child, sb, depth + 1);
                }
                // 处理文本节点
                String ownText = node.ownText().trim();
                if (!ownText.isEmpty()) {
                    sb.append(ownText).append("\n");
                }
            }
            default -> {
                // 未知标签：递归子节点
                for (Element child : node.children()) {
                    convertNode(child, sb, depth + 1);
                }
            }
        }
    }

    private void appendHeading(StringBuilder sb, String text, int level) {
        if (text.isEmpty()) return;
        sb.append("#".repeat(level)).append(" ").append(text).append("\n\n");
    }

    private void convertTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        // 表头
        Elements headerCells = rows.first().select("th, td");
        sb.append("| ");
        for (Element cell : headerCells) {
            sb.append(cell.text().trim().replace("|", "\\|")).append(" | ");
        }
        sb.append("\n| ");
        for (int i = 0; i < headerCells.size(); i++) {
            sb.append("--- | ");
        }
        sb.append("\n");

        // 数据行
        for (int i = 1; i < rows.size(); i++) {
            Elements cells = rows.get(i).select("td, th");
            sb.append("| ");
            for (Element cell : cells) {
                sb.append(cell.text().trim().replace("|", "\\|")).append(" | ");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }
}

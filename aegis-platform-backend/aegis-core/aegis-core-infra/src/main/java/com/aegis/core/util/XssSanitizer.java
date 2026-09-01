package com.aegis.core.util;

/**
 * XSS 输入清洗工具。
 *
 * <p>对用户输入的文本字段进行 HTML 标签转义，防止存储型 XSS 攻击。
 * 采用白名单策略，仅转义危险字符（< > & " ' /），保留其他文本内容。
 *
 *  @author wang.zhen
 */
public final class XssSanitizer {

    private XssSanitizer() {
    }

    /**
     * 清洗单个字符串，将 HTML 特殊字符转义为 HTML 实体。
     *
     * @param input 原始输入
     * @return 清洗后的字符串，null 输入返回 null
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '&':  sb.append("&amp;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                case '/':  sb.append("&#47;"); break;
                case '\\': sb.append("&#92;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 清洗字符串，限制最大长度（超长截断）。
     *
     * @param input     原始输入
     * @param maxLength 最大长度
     * @return 清洗后的字符串
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        String sanitized = sanitize(input);
        if (sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength);
        }
        return sanitized;
    }

    /**
     * 判断字符串是否包含潜在的 XSS 攻击载荷。
     *
     * @param input 待检查的字符串
     * @return true 表示可能含有 XSS 载荷
     */
    public static boolean containsXssPayload(String input) {
        if (input == null) {
            return false;
        }
        String lower = input.toLowerCase();
        return lower.contains("<script")
                || lower.contains("javascript:")
                || lower.contains("onload=")
                || lower.contains("onerror=")
                || lower.contains("onclick=")
                || lower.contains("<iframe")
                || lower.contains("eval(")
                || lower.contains("alert(");
    }
}

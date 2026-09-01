package com.aegis.runtime.integration.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Aegis 工具配置属性。
 *
 * <p>统一前缀 {@code aegis.tools.*}，承载内置工具运行时配置项。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code web-search-url}：自定义联网搜索 API 地址（可选）。
 *       留空时使用内置 Bing 搜索后端（默认）；配置后切换为自定义搜索 API
 *       （如 Tavily/SerpAPI/Bing Search API），需以 GET 方式接收 ?q= 参数并返回搜索结果文本。</li>
 * </ul>
 *
 * <h3>配置示例</h3>
 * <pre>
 * aegis:
 *   tools:
 *     web-search-url: https://api.tavily.com/search
 * </pre>
 *
 * @author wang.zhen
 * @see AegisBuiltinTools#webSearch
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.tools")
public class AegisToolProperties {

    /**
     * 自定义联网搜索 API 地址（可选）。
     *
     * <p>留空时使用内置 Bing 搜索后端（默认）；配置后切换为自定义搜索 API。
     * 自定义 API 需以 GET 方式接收 {@code ?q=} 参数并返回搜索结果文本。
     */
    private String webSearchUrl = "";
}

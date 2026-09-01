package com.aegis.core.autoconfigure;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * 全局 Jackson 配置 —— 彻底解决 JS 与 Java Long 精度丢失问题。
 *
 * <h3>问题根因</h3>
 * JavaScript Number 为 IEEE-754 双精度浮点，安全整数上限 {@code 2^53 - 1 ≈ 9e15}，
 * 而雪花 ID 为 19 位（{@code 2e18} 量级）。前端若以 JSON number 发送 Long ID，
 * 精度必然丢失（如 {@code 2094591688007012354 → 2094591688007012400}）。
 *
 * <h3>本配置做了三件事</h3>
 * <ol>
 *   <li><b>序列化（出参）</b>：所有 {@link Long} 值一律输出为 JSON String（加双引号），
 *       前端直接当作字符串使用即可，无需额外加 {@code @JsonSerialize(ToStringSerializer)}。</li>
 *   <li><b>反序列化（入参）</b>：{@link Long} 字段只接受 JSON String；若收到 JSON number
 *       且绝对值 &gt; {@code 2^53}，抛出清晰的校验错误，提示前端改用字符串传参。</li>
 *   <li><b>兜底</b>：{@link Long} 字段收到 null 或空字符串时按 null 处理，与 Spring 默认行为一致。</li>
 * </ol>
 *
 * <h3>实现方式</h3>
 * 通过 {@link BeanPostProcessor} 在 ObjectMapper Bean 创建后注入自定义 SimpleModule，
 * 直接作用于所有 ObjectMapper 实例（WebFlux / MVC / Gateway 均生效），不依赖 Spring Boot
 * 额外 Jackson 自动配置（{@code @JsonComponent} / {@code Jackson2ObjectMapperBuilderCustomizer}
 * 等），兼容 Spring Boot 3 / 4。
 *
 * <h3>前端契约</h3>
 * 前端 TypeScript 中所有 ID、外键、雪花 ID 字段的类型应声明为 {@code string}（而非
 * {@code string | number}），提交时 {@code JSON.stringify} 会自动把字符串值保持为字符串。
 *
 * @author wang.zhen
 * @see <a href="https://developer.mozilla.org/zh-CN/docs/Web/JavaScript/Reference/Global_Objects/Number/MAX_SAFE_INTEGER">MDN: MAX_SAFE_INTEGER</a>
 */
@AutoConfiguration
@ConditionalOnClass({JsonGenerator.class, ObjectMapper.class})
public class CoreJacksonAutoConfiguration {

    /** JS Number 安全整数上限 2^53 - 1。 */
    private static final double JS_MAX_SAFE_INT = 9_007_199_254_740_991.0;

    /**
     * 将 Long-as-String Module 注入到所有 ObjectMapper Bean。
     *
     * <p>使用 BeanPostProcessor 而非 SimpleModule Bean，是为了兼容 Spring Boot 4 的
     * Jackson 自动配置链路（SimpleModule Bean 在某些场景下不会被自动添加到 WebFlux 的
     * ObjectMapper 中）。
     */
    @Bean
    public BeanPostProcessor longAsStringModuleInjector() {
        final SimpleModule module = buildLongAsStringModule();
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof ObjectMapper mapper) {
                    mapper.registerModule(module);
                }
                return bean;
            }
        };
    }

    /**
     * 构建 Long-as-String 序列化/反序列化模块。
     */
    static SimpleModule buildLongAsStringModule() {
        SimpleModule module = new SimpleModule("core-long-as-string");

        // === 序列化：Long → JSON String ===
        module.addSerializer(Long.class, new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null) {
                    gen.writeNull();
                } else {
                    gen.writeString(value.toString());
                }
            }
        });
        module.addSerializer(Long.TYPE, new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });

        // === 反序列化：Long 只接受 JSON String，拒绝超限 number ===
        module.addDeserializer(Long.class, new SafeLongDeserializer());
        module.addDeserializer(Long.TYPE, new SafeLongDeserializer());

        return module;
    }

    /**
     * 安全的 Long 反序列化器。
     *
     * <p>JSON String → 正常 parse。JSON number → 若值超出 JS 安全整数范围则拒绝
     * （因为从 number 进来的 Long 必然已丢失精度）。null / 空串 → null。
     */
    static class SafeLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken t = p.getCurrentToken();
            if (t == JsonToken.VALUE_NULL) {
                return null;
            }
            if (t == JsonToken.VALUE_STRING) {
                String text = p.getText();
                if (text.isEmpty()) {
                    return null;
                }
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException e) {
                    throw ctxt.weirdStringException(text, Long.class,
                            "无法解析为 Long 类型，雪花 ID 必须以字符串形式传递");
                }
            }
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                double num = p.getDoubleValue();
                if (Math.abs(num) > JS_MAX_SAFE_INT) {
                    throw ctxt.weirdNumberException(num, Long.class,
                            "数值超出 JavaScript 安全整数范围 (±9e15)，大概率是雪花 ID 精度丢失导致。"
                                    + "前端请把该字段声明为 string 类型，提交时作为 JSON 字符串传递。");
                }
                // 安全范围内的小整数（如 32000 / 4096 等非 ID 字段），仍可接受 number
                return (long) num;
            }
            throw ctxt.wrongTokenException(p, Long.class, JsonToken.VALUE_STRING,
                    "雪花 ID 字段只接受 JSON String 或安全范围内的 number");
        }
    }
}

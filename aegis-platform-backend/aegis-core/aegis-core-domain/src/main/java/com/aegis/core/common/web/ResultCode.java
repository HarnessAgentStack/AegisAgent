package com.aegis.core.common.web;

/**
 * 统一响应码枚举。
 *
 * <p>平台所有 HTTP 接口与内部调用的响应码统一枚举，与 {@link Result} 配合提供标准化响应。
 * 编码分区设计，便于按区间定位错误来源：
 *
 * <h3>编码分区</h3>
 * <ul>
 *   <li>2xx：成功（SUCCESS=200）</li>
 *   <li>4xx：客户端错误（参数/认证/授权/资源不存在/配额超限）</li>
 *   <li>5xx：服务端错误（内部异常/依赖不可用）</li>
 * </ul>
 *
 * <p>扩展新响应码时遵循分区约定，保持 code 唯一性与语义清晰。
 *
 * @author wang.zhen
 * @see Result
 */
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "成功"),

    /** 参数错误（校验失败/格式非法） */
    PARAM_ERROR(400, "参数错误"),

    /** 未认证（Token 缺失/失效） */
    UNAUTHORIZED(401, "未认证"),

    /** 无权限（已认证但无访问权限） */
    FORBIDDEN(403, "无权限"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 方法不允许（HTTP 方法不支持） */
    METHOD_NOT_ALLOWED(405, "方法不允许"),

    /** 冲突（状态冲突/重复操作） */
    CONFLICT(409, "资源冲突"),

    /** 不支持的媒体类型 */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的媒体类型"),

    /** 语义无法处理（请求格式正确但语义错误） */
    UNPROCESSABLE_ENTITY(422, "请求语义错误"),

    /** 审批未通过（HITL 审批被拒绝） */
    APPROVAL_REJECTED(422, "审批未通过"),

    /** 配额超限（Token/会话/资源配额耗尽） */
    QUOTA_EXCEEDED(429, "配额超限"),

    /** 请求过多（限流触发） */
    TOO_MANY_REQUESTS(429, "请求过多"),

    /** 内部错误（未知异常） */
    INTERNAL_ERROR(500, "内部错误"),

    /** 未实现（功能尚不可用） */
    NOT_IMPLEMENTED(501, "未实现"),

    /** 依赖服务不可用（模型/沙箱/存储不可用） */
    SERVICE_UNAVAILABLE(503, "依赖服务不可用"),

    /** 网关超时（下游响应超时） */
    GATEWAY_TIMEOUT(504, "网关超时"),

    /** HITL 等待审批（智能体暂停等待人工确认） */
    HITL_PENDING(409, "等待人工确认"),

    /** 数据校验失败 */
    DATA_INTEGRITY_ERROR(422, "数据一致性错误");

    /** 响应码 */
    private final int code;

    /** 响应消息 */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取响应码。
     *
     * @return HTTP 风格响应码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取响应消息。
     *
     * @return 默认消息文案
     */
    public String getMessage() {
        return message;
    }
}

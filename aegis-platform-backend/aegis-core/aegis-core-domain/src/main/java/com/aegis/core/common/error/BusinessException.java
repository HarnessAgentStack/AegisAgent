package com.aegis.core.common.error;

import lombok.Getter;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;

/**
 * 业务异常基类。
 *
 * <p>平台业务层抛出的可预期异常基类，携带标准 {@link ResultCode} 响应码与可读消息。
 * 由全局异常处理器统一捕获并转换为 {@link Result} 失败响应，区分于不可预期的系统异常。
 *
 * <h3>使用约定</h3>
 * <ul>
 *   <li>可预期业务错误（如配额超限、审批拒绝、状态冲突）抛出本类或其子类</li>
 *   <li>不可预期系统错误抛 {@link RuntimeException}，由处理器归为 INTERNAL_ERROR</li>
 *   <li>子类可扩展为具体领域异常（如 QuotaExceededException、ApprovalRejectedException）</li>
 * </ul>
 *
 * <h3>异常处理流</h3>
 * <ul>
 *   <li>业务层抛出 → 全局异常处理器捕获 → 按 resultCode 转换为 HTTP 状态码与响应体</li>
 *   <li>响应码 4xx 映射对应 HTTP 4xx，5xx 映射 HTTP 5xx</li>
 * </ul>
 *
 * @author wang.zhen
 * @see ResultCode
 * @see Result
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 响应码枚举，决定 HTTP 状态码与错误语义 */
    private final ResultCode resultCode;

    /**
     * 构造业务异常（使用枚举默认消息）。
     *
     * @param resultCode 响应码枚举
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 构造业务异常（自定义消息）。
     *
     * @param resultCode 响应码枚举
     * @param message    自定义消息
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    /**
     * 构造业务异常（携带原因）。
     *
     * @param resultCode 响应码枚举
     * @param message    自定义消息
     * @param cause      原始异常
     */
    public BusinessException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }
}

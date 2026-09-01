package com.aegis.core.common.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应封装。
 *
 * <p>平台所有 HTTP 接口的统一返回结构，承载状态码、消息、数据与全链路追踪ID。
 * 配合 {@link ResultCode} 提供标准化响应，便于问题定位。
 *
 * <h3>使用约定</h3>
 * <ul>
 *   <li>成功响应：{@link #success(Object)}，code=200</li>
 *   <li>业务失败：{@link #fail(ResultCode)} 或 {@link #fail(ResultCode, String)}，携带错误码</li>
 *   <li>异常响应：由全局异常处理器统一转换为失败响应</li>
 *   <li>traceId 从日志上下文填充，便于串联全链路日志</li>
 * </ul>
 *
 * @author wang.zhen
 * @param <T> 数据载荷类型
 * @see ResultCode
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 响应码，对应 {@link ResultCode#getCode()} */
    private int code;

    /** 响应消息 */
    private String message;

    /** 业务数据载荷 */
    private T data;

    /** 全链路追踪ID，用于日志串联与问题定位 */
    private String traceId;

    /**
     * 构建成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(ResultCode.SUCCESS.getMessage());
        result.setData(data);
        return result;
    }

    /**
     * 构建无数据的成功响应（用于写操作返回）。
     *
     * @param <T> 数据类型
     * @return 成功响应（data 为 null）
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 构建失败响应（使用枚举默认消息）。
     *
     * @param resultCode 响应码枚举
     * @param <T>        数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode, resultCode.getMessage());
    }

    /**
     * 构建失败响应（自定义消息）。
     *
     * @param resultCode 响应码枚举
     * @param message    自定义消息
     * @param <T>        数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        Result<T> result = new Result<>();
        result.setCode(resultCode.getCode());
        result.setMessage(message);
        return result;
    }

    /**
     * 判断是否成功。
     *
     * @return true 表示业务成功
     */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}

package com.aegis.core.common.web;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页请求基类。
 *
 * <p>平台所有分页查询接口的统一入参基类，承载页码、页大小与排序条件。
 * 各业务模块的查询 DTO 继承本类以复用分页与排序语义，避免重复定义。
 *
 * <h3>使用约定</h3>
 * <ul>
 *   <li>页码从 1 开始，pageSize 默认 10，上限 200（防止单页过大拖垮数据库）</li>
 *   <li>排序字段（sortField）使用下划线命名（对应数据库列名），sortOrder 为 ASC/DESC</li>
 *   <li>排序字段需做白名单校验，防止 SQL 注入</li>
 * </ul>
 *
 * @author wang.zhen
 * @see PageResult
 */
@Data
public class PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 默认页大小 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    /** 最大页大小，防止单页过大 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 页码，从 1 开始 */
    private Integer pageNum = DEFAULT_PAGE_NUM;

    /** 每页条数，默认 10，上限 200 */
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    /** 排序字段（数据库列名，下划线命名） */
    private String sortField;

    /** 排序方向：ASC / DESC */
    private String sortOrder;

    /**
     * 获取规范化后的页码（不小于 1）。
     *
     * @return 规范化页码
     */
    public int normalizedPageNum() {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    /**
     * 获取规范化后的页大小（1~200）。
     *
     * @return 规范化页大小
     */
    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 计算数据库 OFFSET 偏移量。
     *
     * @return 分页偏移量
     */
    public long offset() {
        return (long) (normalizedPageNum() - 1) * normalizedPageSize();
    }
}

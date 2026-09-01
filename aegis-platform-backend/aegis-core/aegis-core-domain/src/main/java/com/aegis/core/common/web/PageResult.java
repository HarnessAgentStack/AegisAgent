package com.aegis.core.common.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果基类。
 *
 * <p>平台所有分页查询接口的统一返回结构，承载当前页数据、总条数与分页元信息。
 * 与 {@link PageRequest} 配对使用，由各业务模块的查询服务组装返回。
 *
 * <h3>使用约定</h3>
 * <ul>
 *   <li>空结果使用 {@link #empty(PageRequest)} 构建零条记录的分页结果</li>
 *   <li>total 为满足条件的总记录数，list 为当前页数据</li>
 *   <li>pageNum/pageSize 回显请求参数</li>
 * </ul>
 *
 * @author wang.zhen
 * @param <T> 列表元素类型
 * @see PageRequest
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页数据 */
    private List<T> list;

    /** 当前页码 */
    private Integer pageNum;

    /** 每页条数 */
    private Integer pageSize;

    /**
     * 构建分页结果。
     *
     * @param list       当前页数据
     * @param total      总记录数
     * @param pageNum    当前页码
     * @param pageSize   每页条数
     * @param <T>        元素类型
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setList(list);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 构建空分页结果。
     *
     * @param request 分页请求（用于回显页码与页大小）
     * @param <T>     元素类型
     * @return 空分页结果
     */
    public static <T> PageResult<T> empty(PageRequest request) {
        PageResult<T> result = new PageResult<>();
        result.setList(Collections.emptyList());
        result.setTotal(0L);
        result.setPageNum(request == null ? null : request.normalizedPageNum());
        result.setPageSize(request == null ? null : request.normalizedPageSize());
        return result;
    }

    /**
     * 计算总页数。
     *
     * @return 总页数
     */
    public int getTotalPages() {
        if (pageSize == null || pageSize == 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }
}

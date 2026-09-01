package com.aegis.dal.observe;

import com.aegis.core.common.web.PageResult;

import java.util.List;

public class ObservePage {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setList(records);
        result.setTotal(total);
        result.setPageNum(page);
        result.setPageSize(size);
        return result;
    }
}
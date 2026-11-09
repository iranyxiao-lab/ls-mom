package com.lsmom.crud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 通用台账限额：列表每页上限、导出条数上限、导入行数上限。 */
@ConfigurationProperties(prefix = "lsmom.crud")
public record CrudProperties(Integer maxPageSize, Integer exportMaxRows, Integer importMaxRows) {

    public CrudProperties {
        maxPageSize = maxPageSize == null ? 200 : maxPageSize;
        exportMaxRows = exportMaxRows == null ? 10000 : exportMaxRows;
        importMaxRows = importMaxRows == null ? 5000 : importMaxRows;
    }

    public static CrudProperties defaults() {
        return new CrudProperties(null, null, null);
    }
}

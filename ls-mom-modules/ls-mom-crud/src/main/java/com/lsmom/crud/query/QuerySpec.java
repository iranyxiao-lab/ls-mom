package com.lsmom.crud.query;

import java.util.List;

/**
 * 列表查询描述。字段、操作符、排序、关联字段（{@code ref.field}）、分组与聚合都只能取白名单内的值，
 * 不接受任何 SQL 片段；值一律参数绑定。
 */
public record QuerySpec(List<Filter> filters, List<Sort> sort, Integer page, Integer size, List<String> expand,
                        List<String> groupBy, List<Aggregate> aggregates) {

    public QuerySpec {
        filters = filters == null ? List.of() : filters;
        sort = sort == null ? List.of() : sort;
        expand = expand == null ? List.of() : expand;
        groupBy = groupBy == null ? List.of() : groupBy;
        aggregates = aggregates == null ? List.of() : aggregates;
    }

    public static QuerySpec all() {
        return new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(), List.of());
    }

    public QuerySpec withFilters(List<Filter> fs) {
        return new QuerySpec(fs, sort, page, size, expand, groupBy, aggregates);
    }

    public record Filter(String field, String op, Object value) {
    }

    public record Sort(String field, String dir) {
    }

    /** fn：count / sum / avg / min / max；alias 只能是字母数字。 */
    public record Aggregate(String fn, String field, String alias) {
    }

    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    public int sizeOrDefault(int max) {
        return size == null || size < 1 ? 20 : Math.min(size, max);
    }
}

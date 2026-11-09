package com.lsmom.form;

/** 视图校验问题：path 为 JSON 指针风格的位置（如 /fields/3/key），code 为稳定错误编码。 */
public record ViewIssue(String path, String code, String message) {
}

package com.lsmom.form;

/** 视图类型：录入表单、列表、详情、打印、移动端、看板绑定。 */
public enum ViewType {
    FORM("form"), LIST("list"), DETAIL("detail"), PRINT("print"), MOBILE("mobile"), DASHBOARD("dashboard");

    private final String schemaFile;

    ViewType(String schemaFile) {
        this.schemaFile = schemaFile;
    }

    public String schemaUri() {
        return ViewSchemaValidator.SCHEMA_BASE + "/" + schemaFile + ".schema.json";
    }
}

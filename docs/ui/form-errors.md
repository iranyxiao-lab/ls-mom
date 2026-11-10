# 字段错误与就近校验

## 服务端错误结构

所有 `VALIDATION_FAILED` 响应的 `data.details` 都包含：

- `errors`：一个数组，每项为 `{key, field, table?, rowIndex?, code, message, fix?}`。
  - 子表单元格的 `key` 为 `子表[行].列`（行号从 0 开始），同时拆成 `table`、`rowIndex` 和 `field`（`子表.列`）。
  - `message` 不带"第 n 行"前缀。
  - `fix` 是修复提示，可能没有。
- `fields`：`键 → 文字`，为兼容旧调用方保留。子表单元格的文字带"第 n 行："前缀。

`code` 的取值如下。不经表单的校验（例如 Concept 字段的长度上限）没有细分编码，记为 `INVALID`。

| 编码 | 含义 |
|---|---|
| `REQUIRED` | 必填 |
| `READONLY` | 只读字段被修改 |
| `MIN_LENGTH` / `MAX_LENGTH` | 长度不符 |
| `PATTERN` | 格式不符 |
| `NOT_NUMBER` / `MIN` / `MAX` | 不是数字或超出范围 |
| `OPTION` / `DICT` | 取值不在选项或字典中 |
| `REF` | 引用对象不存在、已停用或不在允许范围 |
| `ROWS_MIN` / `ROWS_MAX` | 子表行数不符 |
| `RULE` | 表单规则不满足 |

生成方式：`com.lsmom.core.error.FieldErrors.complete(details)` 在 `LsBizExceptionHandler` 中统一补全 `errors`，编码取自 `details.codes`，修复提示取自 `details.fixes`（由 `FormRecordValidator` 给出）。

## 前端

- 请求失败时，返回带 `code`、`errorCode`、`details`、`trace` 的 Error。调用方用 `SchemaForm.setServerErrors(e)` 把字段错误回填到字段和子表单元格，返回值表示是否识别为字段错误。
- 字段失焦时立即校验，并在字段旁显示错误；这时不弹出汇总。
- 提交时校验失败或服务端拒绝：表单顶部显示「有 N 处需要修改」汇总，每项写明字段（子表写成「子表名 · 列名」）、原因和修复提示，点击即可定位。页面会自动滚动到第一个错误并聚焦。
- 出错的子表单元格会标红并显示原因；改动该单元格后错误消失。

端到端测试见 `tests/ui/app/form-errors.spec.ts`：借还单子表第 3 行工器具名称超过 Concept 的长度上限，这个错误只有服务端能发现。

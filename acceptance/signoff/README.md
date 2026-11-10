# 签收记录

验收通过后，由产品负责人确认验收报告并新增一份签收记录。**只能新增，不能修改或删除历史记录**——CI 的 `signoff-guard`
（`node tools/signoff-guard.ts <基线提交>`）会拒绝改动已有记录的提交。需要更正时新增一份记录说明更正内容并引用原记录。

## 步骤

1. 确认 `runs/<run>/report.md` 结论为"通过"（`node tools/report.ts <run>`），记下报告的提交号。
2. 复制 `TEMPLATE.md` 为 `<yyyymmdd>-<提交号>.md`，填写全部字段。
3. 提交并推送到 GitHub，确认 CI 通过。

签收后若修改了已验收功能，按 `acceptance/README.md`"复验"一节重跑受影响 spec 的用例与旅程，在新报告中追加复验结果后再签收（新增一份记录）。

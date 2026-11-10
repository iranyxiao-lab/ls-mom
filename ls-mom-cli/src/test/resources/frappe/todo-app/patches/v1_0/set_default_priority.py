# 自编样例 Patch：转换器不执行，标记需人工
import frappe

def execute():
    frappe.db.sql("update tabToDo set priority = 'Medium' where priority is null")

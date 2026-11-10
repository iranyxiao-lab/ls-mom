import { ElMessage } from 'element-plus';

/**
 * 动作完成提示：有提醒（如资源时间冲突）时仍说明已经完成，再附上提醒，提醒停留更久。
 * 只显示提醒会让人以为动作没有执行。
 */
export function notifyDone(done: string, warnings?: string[] | null) {
  if (warnings?.length) ElMessage.warning({ message: `${done}。注意：${warnings.join('；')}`, duration: 6000 });
  else ElMessage.success(done);
}

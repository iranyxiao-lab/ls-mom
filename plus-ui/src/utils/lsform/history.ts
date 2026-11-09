/** 设计器撤销/重做：保存 LS FormSchema 快照（序列化后去重），默认上限 100 步（≥50）。 */
export class History<T> {
  private stack: string[] = [];
  private index = -1;

  constructor(private readonly limit = 100) {}

  /** 记录当前状态；与栈顶相同则忽略；在中间位置记录会丢弃后面的重做分支。返回是否记录了新步骤。 */
  push(state: T): boolean {
    const s = JSON.stringify(state);
    if (this.index >= 0 && this.stack[this.index] === s) return false;
    this.stack = this.stack.slice(0, this.index + 1);
    this.stack.push(s);
    if (this.stack.length > this.limit) this.stack.shift();
    this.index = this.stack.length - 1;
    return true;
  }

  undo(): T | undefined {
    if (!this.canUndo) return undefined;
    this.index--;
    return JSON.parse(this.stack[this.index]);
  }

  redo(): T | undefined {
    if (!this.canRedo) return undefined;
    this.index++;
    return JSON.parse(this.stack[this.index]);
  }

  get canUndo() {
    return this.index > 0;
  }

  get canRedo() {
    return this.index < this.stack.length - 1;
  }

  get size() {
    return this.stack.length;
  }
}

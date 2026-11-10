/** 成功提示：文案统一为"已＋动作"，可带"撤销"（低风险操作的撤销见 useUndoable）。 */
import { h } from 'vue';
import { ElMessage } from 'element-plus';

export function lsSuccess(message: string, opts: { undo?: () => void; duration?: number } = {}) {
  const handle = ElMessage({
    type: 'success',
    duration: opts.duration ?? (opts.undo ? 5000 : 2500),
    grouping: true,
    message: opts.undo
      ? h('span', { class: 'ls-toast' }, [
          h('span', message),
          h(
            'button',
            {
              type: 'button',
              class: 'ls-toast__undo',
              'data-testid': 'toast-undo',
              onClick: () => {
                handle.close();
                opts.undo!();
              }
            },
            '撤销'
          )
        ])
      : message
  });
  return handle;
}

/** 当前是否应减少动态效果（系统设置或用户偏好）。 */
export function prefersReducedMotion(): boolean {
  return document.documentElement.dataset.motion === 'reduced' || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

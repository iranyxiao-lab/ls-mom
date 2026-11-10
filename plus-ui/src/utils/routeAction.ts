/**
 * 地址里的动作参数（?action=new）：命令面板等入口跳到页面时直接执行页面上的动作（如打开登记对话框），
 * 执行后从地址中去掉，刷新或切回页签不会重复执行。
 */
import { onActivated, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';

export function useRouteAction(name: string, run: () => unknown): void {
  const route = useRoute();
  const router = useRouter();
  let pending = false;
  const check = () => {
    // keep-alive 页面首次打开时 onMounted 与 onActivated 都会触发
    if (pending || route?.query?.action !== name) return;
    pending = true;
    const { action: _drop, ...rest } = route.query;
    void router.replace({ path: route.path, query: rest }).then(() => {
      pending = false;
      run();
    });
  };
  onMounted(check);
  onActivated(check);
}

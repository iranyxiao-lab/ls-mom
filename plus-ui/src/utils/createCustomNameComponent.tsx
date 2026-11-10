/**
 * 后台返回的路由动态生成name 解决缓存问题
 * 感谢 @fourteendp
 * 详见 https://github.com/vbenjs/vue-vben-admin/issues/3927
 */
import { Component, defineComponent, getCurrentInstance, h } from 'vue';
import { useKeepScroll } from '@/utils/pageState';

interface Options {
  name?: string;
}

export function createCustomNameComponent(loader: () => Promise<any>, options: Options = {}): () => Promise<Component> {
  const { name } = options;
  let component: Component | null = null;

  const load = async () => {
    try {
      const { default: loadedComponent } = await loader();
      component = loadedComponent;
    } catch (error) {
      console.error(`Cannot resolve component ${name}, error:`, error);
    }
  };

  return async () => {
    if (!component) {
      await load();
    }

    return Promise.resolve(
      defineComponent({
        name,
        setup() {
          // 页签切换时恢复滚动位置（页面被 keep-alive 缓存时生效）
          const self = getCurrentInstance();
          useKeepScroll(() => (self?.proxy?.$el as Element | undefined) ?? null);
          return () => h(component as Component);
        }
      })
    );
  };
}

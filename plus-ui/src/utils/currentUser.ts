import { ref } from 'vue';

/** 当前登录用户的编号（由用户 store 在获取用户信息时写入）。不依赖请求模块，渲染器等基础组件可直接使用。 */
export const currentUserId = ref<string | number>('');

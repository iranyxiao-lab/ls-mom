/** 首页卡片取数：命名 Query（返回 rows 与 total）。 */
import { onMounted, ref } from 'vue';
import { metricApi } from '@/api/lsmom/metric';

export function useQueryCard(code: string, params: Record<string, unknown> = { limit: 5 }) {
  const rows = ref<Record<string, any>[]>([]);
  const total = ref<number | null>(null);
  const loading = ref(true);
  const error = ref('');
  async function load() {
    loading.value = true;
    error.value = '';
    try {
      const r = await metricApi.namedQuery(code, params);
      rows.value = r.rows;
      total.value = r.rows.length ? Number(r.rows[0].total ?? r.rows.length) : 0;
    } catch (e: any) {
      error.value = e?.message || '数据加载失败';
    } finally {
      loading.value = false;
    }
  }
  onMounted(load);
  return { rows, total, loading, error, load };
}

/** 本人菜单中第一个存在的页面（卡片的"查看全部"指向它）。 */
export function firstAvailable(paths: string[], menu: Set<string>): string | undefined {
  return paths.find((p) => menu.has(p));
}

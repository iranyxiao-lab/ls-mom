/**
 * ECharts 主题：从设计令牌生成，颜色取运行期 CSS 变量的计算值（品牌色、明暗切换后自动重新注册）。
 * 用法：const theme = useChartTheme(); echarts.init(el, theme.value)；watch(theme, 重新 init)。
 */
import * as echarts from 'echarts/core';
import { ref, type Ref } from 'vue';
import { echartsTheme } from './build';
import { cssVar, onThemeChange } from './theme';

let seq = 0;
const current = ref('');

/** 按当前 CSS 变量注册主题，返回主题名（每次变化生成新名，便于图表判断是否需要重建）。 */
export function registerChartTheme(): string {
  const name = `ls-${++seq}`;
  echarts.registerTheme(name, echartsTheme((v) => cssVar(v)));
  current.value = name;
  return name;
}

let bound = false;
export function useChartTheme(): Ref<string> {
  if (!current.value) registerChartTheme();
  if (!bound) {
    bound = true;
    onThemeChange(() => requestAnimationFrame(() => registerChartTheme()));
  }
  return current;
}

import type { Meta, StoryObj } from '@storybook/vue3-vite';
import { defineComponent, h, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { useChartTheme } from '../echarts';
import { primitive, semanticColor } from '../tokens';

const meta: Meta = { title: '设计系统/令牌' };
export default meta;

/** 语义颜色、间距、圆角、字号一览。 */
export const Overview: StoryObj = {
  render: () => ({
    setup() {
      return { colors: Object.keys(semanticColor.light), space: Object.keys(primitive.space), radius: Object.keys(primitive.radius), sizes: Object.keys(primitive.fontSize) };
    },
    template: `
      <section style="display:grid;gap:var(--ls-space-6);color:var(--ls-color-text)">
        <div>
          <h3>语义颜色</h3>
          <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:var(--ls-space-2)">
            <div v-for="c in colors" :key="c" style="display:flex;align-items:center;gap:var(--ls-space-2)">
              <span :style="{width:'32px',height:'32px',borderRadius:'var(--ls-radius-md)',border:'1px solid var(--ls-color-border-light)',background:'var(--ls-color-'+c+')'}"></span>
              <code style="font-size:var(--ls-font-size-xs)">--ls-color-{{c}}</code>
            </div>
          </div>
        </div>
        <div>
          <h3>间距</h3>
          <div v-for="s in space" :key="s" style="display:flex;align-items:center;gap:var(--ls-space-3)">
            <code style="width:120px;font-size:var(--ls-font-size-xs)">--ls-space-{{s}}</code>
            <span :style="{height:'12px',width:'var(--ls-space-'+s+')',background:'var(--ls-color-primary)'}"></span>
          </div>
        </div>
        <div style="display:flex;gap:var(--ls-space-4)">
          <div v-for="r in radius" :key="r" :style="{width:'64px',height:'48px',border:'1px solid var(--ls-color-border)',borderRadius:'var(--ls-radius-'+r+')',display:'grid',placeItems:'center',fontSize:'var(--ls-font-size-xs)'}">{{r}}</div>
        </div>
        <div>
          <div v-for="f in sizes" :key="f" :style="{fontSize:'var(--ls-font-size-'+f+')'}">字号 {{f}}：班组长确认交接班</div>
        </div>
      </section>`
  })
};

const BrandChart = defineComponent({
  setup() {
    const el = ref<HTMLDivElement>();
    const theme = useChartTheme();
    let chart: echarts.ECharts | undefined;
    const draw = () => {
      if (!el.value) return;
      chart?.dispose();
      chart = echarts.init(el.value, theme.value, { renderer: 'svg' });
      chart.setOption({ animation: false, xAxis: { type: 'category', data: ['甲班', '乙班', '丙班'] }, yAxis: { type: 'value' }, series: [{ type: 'bar', data: [12, 18, 9] }] });
    };
    onMounted(draw);
    watch(theme, draw);
    return () => h('div', { ref: el, 'data-testid': 'brand-chart', style: 'width:360px;height:200px' });
  }
});

/** 品牌换色：按钮、链接、焦点环、图表主色都只来自 --ls-color-primary。 */
export const Brand: StoryObj = {
  render: () => ({
    components: { BrandChart },
    template: `
      <div style="display:grid;gap:var(--ls-space-4);justify-items:start">
        <el-button type="primary" data-testid="brand-button">提交审核</el-button>
        <el-link type="primary" data-testid="brand-link">查看交接记录</el-link>
        <el-button data-testid="brand-focus">键盘聚焦查看焦点环</el-button>
        <BrandChart />
      </div>`
  })
};

import type { Meta, StoryObj } from '@storybook/vue3-vite';
import { Button as VanButton, Field as VanField, Tag as VanTag } from 'vant';

const meta: Meta = { title: '设计系统/组件库一致性' };
export default meta;

/** Element Plus（桌面）与 Vant（移动）并排：主色、圆角、字号都来自同一套令牌。 */
export const SideBySide: StoryObj = {
  render: () => ({
    components: { VanButton, VanField, VanTag },
    template: `
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:var(--ls-space-8);color:var(--ls-color-text)">
        <section data-testid="parity-el" style="display:grid;gap:var(--ls-space-3);justify-items:start">
          <h3>Element Plus</h3>
          <el-button type="primary" data-testid="el-primary">开工</el-button>
          <el-tag type="success">已完成</el-tag>
          <el-input model-value="一车间甲班" aria-label="班组" style="width:240px" />
        </section>
        <section data-testid="parity-van" style="display:grid;gap:var(--ls-space-3);justify-items:start">
          <h3>Vant</h3>
          <van-button type="primary" data-testid="van-primary">开工</van-button>
          <van-tag type="success">已完成</van-tag>
          <van-field model-value="一车间甲班" label="班组" style="width:240px" />
        </section>
      </div>`
  })
};

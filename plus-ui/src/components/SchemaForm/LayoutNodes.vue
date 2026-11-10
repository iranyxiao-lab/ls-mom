<template>
  <template v-for="(n, i) in nodes" :key="n.key ?? n.type + i">
    <template v-if="n.type === 'field' && fieldOf(n.key)">
      <el-form-item
        v-if="ctx.access[n.key!]?.visible !== false && (!ctx.only || ctx.only.has(n.key!))"
        :label="fieldOf(n.key)!.label ?? n.key"
        :required="!!ctx.access[n.key!]?.required"
        :error="ctx.errors[n.key!]"
        :data-testid="'field-' + n.key"
        :data-field-key="n.key"
      >
        <template v-if="ctx.origin?.[n.key!] || fieldOf(n.key)!.help" #label>
          {{ fieldOf(n.key)!.label ?? n.key }}
          <!-- 字段悬浮说明：鼠标移上或键盘聚焦时显示 -->
          <el-tooltip v-if="fieldOf(n.key)!.help" :content="fieldOf(n.key)!.help" placement="top">
            <span class="ls-form__help-icon" tabindex="0" role="img" :aria-label="`说明：${fieldOf(n.key)!.help}`" :data-testid="'help-' + n.key"
              >?</span
            >
          </el-tooltip>
          <span v-if="ctx.origin?.[n.key!]" :class="['ls-form__origin', 'is-' + ctx.origin[n.key!]]" :data-testid="'origin-' + n.key">{{
            ORIGIN_LABEL[ctx.origin[n.key!]]
          }}</span>
        </template>
        <component
          :is="ctx.platform === 'mobile' ? MobileFieldWidget : FieldWidget"
          :field="fieldOf(n.key)!"
          :model-value="ctx.model[n.key!]"
          :editable="!!ctx.access[n.key!]?.editable"
          :api="ctx.api"
          :org-id="ctx.orgId"
          :model="ctx.model"
          :capture="ctx.capture"
          @update:model-value="(v: unknown) => ctx.set(n.key!, v)"
          @scan-error="(m: string | null) => ctx.setCellErrors?.({ [n.key!]: m })"
        />
      </el-form-item>
    </template>
    <template v-else-if="n.type === 'subTable' && tableOf(n.key)">
      <SubTableEditor
        v-if="ctx.access[n.key!]?.visible !== false && (!ctx.only || ctx.only.has(n.key!))"
        :table="tableOf(n.key)!"
        :model-value="ctx.model[n.key!]"
        :access="ctx.access"
        :errors="ctx.errors"
        :api="ctx.api"
        :org-id="ctx.orgId"
        class="ls-form__block"
        @update:model-value="(v: unknown) => ctx.set(n.key!, v)"
        @cell-errors="(e: Record<string, string | null>) => ctx.setCellErrors?.(e)"
      />
    </template>
    <el-row v-else-if="n.type === 'row'" :gutter="16">
      <LayoutNodes :nodes="n.children ?? []" :ctx="ctx" />
    </el-row>
    <el-col v-else-if="n.type === 'col'" :xs="24" :sm="ctx.single ? 24 : (n.span ?? 24)">
      <LayoutNodes :nodes="n.children ?? []" :ctx="ctx" />
    </el-col>
    <fieldset v-else-if="n.type === 'group' || n.type === 'panel'" class="ls-form__group">
      <legend v-if="n.title">{{ n.title }}</legend>
      <LayoutNodes :nodes="n.children ?? []" :ctx="ctx" />
    </fieldset>
    <el-tabs v-else-if="n.type === 'tabs'" class="ls-form__block">
      <el-tab-pane v-for="(t, j) in n.children ?? []" :key="j" :label="t.title ?? '页签 ' + (j + 1)">
        <LayoutNodes :nodes="t.children ?? []" :ctx="ctx" />
      </el-tab-pane>
    </el-tabs>
    <el-collapse v-else-if="n.type === 'collapse'" :model-value="openPanels(n)" class="ls-form__block">
      <el-collapse-item v-for="(p, j) in n.children ?? []" :key="j" :name="j" :title="p.title ?? '分组 ' + (j + 1)">
        <LayoutNodes :nodes="p.children ?? []" :ctx="ctx" />
      </el-collapse-item>
    </el-collapse>
    <el-divider v-else-if="n.type === 'divider'">{{ n.title }}</el-divider>
    <p v-else-if="n.type === 'text' || n.type === 'description'" class="ls-form__text">{{ n.text }}</p>
    <el-card v-else-if="n.type === 'metricCard'" shadow="never" class="ls-form__block" :data-testid="'metric-' + n.metric">
      <div class="ls-form__metric-title">{{ n.title ?? n.metric }}</div>
      <slot name="metric" :node="n">
        <div class="ls-form__metric-value">{{ ctx.metrics?.[n.metric!] ?? '—' }}</div>
      </slot>
    </el-card>
    <el-card v-else-if="n.type === 'relatedSummary' || n.type === 'relatedList'" shadow="never" class="ls-form__block">
      <div class="ls-form__metric-title">{{ n.title ?? n.concept }}</div>
      <div class="ls-form__text">{{ ctx.related?.[n.key ?? n.concept!] ?? '保存后显示关联记录' }}</div>
    </el-card>
  </template>
</template>

<script setup lang="ts">
import { defineAsyncComponent } from 'vue';
import type { LayoutNode } from '@/types/lsView';
import type { RenderCtx } from './types';
import FieldWidget from './FieldWidget.vue';
// 移动端控件按需加载，不进桌面首屏
const MobileFieldWidget = defineAsyncComponent(() => import('./MobileFieldWidget.vue'));
import { ORIGIN_LABEL } from '@/utils/lsform/smartDefaults';
import SubTableEditor from './SubTableEditor.vue';

defineOptions({ name: 'LayoutNodes' });

const props = defineProps<{ nodes: LayoutNode[]; ctx: RenderCtx }>();

const fieldOf = (k?: string) => (k ? props.ctx.fields.get(k) : undefined);
const tableOf = (k?: string) => (k ? props.ctx.tables.get(k) : undefined);
const openPanels = (n: LayoutNode) => (n.children ?? []).map((c, j) => (c.collapsed ? -1 : j)).filter((j) => j >= 0);
</script>

<style scoped>
.ls-form__group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 16px 0;
  margin: 0 0 16px;
}
.ls-form__group legend {
  padding: 0 6px;
  font-weight: 600;
}
.ls-form__block {
  margin-bottom: 16px;
}
.ls-form__help-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  margin-left: 2px;
  border-radius: 50%;
  border: 1px solid var(--el-text-color-secondary);
  color: var(--el-text-color-secondary);
  font-size: 10px;
  line-height: 1;
  cursor: help;
  vertical-align: middle;
}
.ls-form__help-icon:focus-visible {
  outline: 2px solid var(--el-color-primary);
}
.ls-form__text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.ls-form__origin {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  font-weight: 400;
  color: var(--el-text-color-primary);
  background: var(--el-color-primary-light-8);
}
/* 上次填写的值与系统带入的值用不同颜色区分 */
.ls-form__origin.is-remembered {
  background: var(--el-color-warning-light-7);
}
.ls-form__metric-title {
  color: var(--el-text-color-secondary);
}
.ls-form__metric-value {
  font-size: 24px;
  font-weight: 600;
}
</style>

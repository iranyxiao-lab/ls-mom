<template>
  <div class="schema-table">
    <div v-if="schema" class="schema-table__search">
      <el-input
        v-model="keyword"
        class="schema-table__keyword"
        clearable
        :placeholder="`搜索${keywordHint}`"
        aria-label="关键字搜索"
        data-testid="keyword"
        @keyup.enter="search"
        @clear="search"
      />
      <div v-if="chips.length" class="schema-table__chips" role="group" aria-label="快速筛选">
        <el-check-tag
          v-for="c in chips"
          :key="c.key"
          :checked="chipOn(c)"
          :aria-pressed="chipOn(c)"
          :data-testid="'chip-' + c.key"
          @change="toggleChip(c)"
          >{{ c.label }}</el-check-tag
        >
      </div>
      <el-button v-if="filterFields.length" data-testid="adv-open" @click="advOpen = true"
        >高级筛选<span v-if="formCount">（{{ formCount }}）</span></el-button
      >
      <template v-if="viewApi">
        <span class="schema-table__grow" />
        <el-select
          v-model="activeViewId"
          clearable
          placeholder="全部记录"
          aria-label="选择视图"
          style="width: 200px"
          data-testid="view-select"
          @change="onViewChange"
        >
          <el-option v-for="v in views" :key="v.id" :label="viewLabel(v)" :value="v.id" :data-testid="'view-option-' + v.id" />
        </el-select>
        <el-button data-testid="view-save" @click="openSave">保存视图</el-button>
        <el-button v-if="activeView" data-testid="view-default" @click="toggleDefault">{{
          activeView.id === defaultId ? '取消默认' : '设为默认'
        }}</el-button>
        <el-button v-if="activeView?.mine" type="danger" plain data-testid="view-delete" @click="deleteView">删除视图</el-button>
      </template>
    </div>
    <div v-if="schema && tags.length" class="schema-table__conds" role="list" aria-label="当前筛选条件" data-testid="conds">
      <el-tag v-for="t in tags" :key="t.key" role="listitem" closable :data-testid="'cond-' + t.field" @close="removeTag(t)">{{ t.label }}</el-tag>
      <el-button link type="primary" data-testid="cond-clear" @click="reset">清除全部</el-button>
    </div>

    <el-drawer v-model="advOpen" title="高级筛选" size="420px" append-to-body data-testid="adv-drawer">
      <el-form v-if="schema" :model="filterModel" label-position="top" data-testid="filters" @submit.prevent>
        <el-form-item v-for="f in filterFields" :key="f.code" :label="f.label">
          <el-select
            v-if="filterOf(f).widget === 'select'"
            v-model="filterModel[f.code]"
            clearable
            :placeholder="'全部'"
            style="width: 100%"
            :data-testid="'filter-' + f.code"
          >
            <el-option v-for="o in f.options || []" :key="o" :label="f.optionLabels?.[o] ?? o" :value="o" />
          </el-select>
          <el-select
            v-else-if="filterOf(f).widget === 'boolean'"
            v-model="filterModel[f.code]"
            clearable
            style="width: 100%"
            :data-testid="'filter-' + f.code"
          >
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
          <el-date-picker
            v-else-if="filterOf(f).widget === 'dateRange'"
            v-model="filterModel[f.code]"
            :type="f.type === 'DATE' ? 'daterange' : 'datetimerange'"
            :value-format="f.type === 'DATE' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
            range-separator="至"
            :data-testid="'filter-' + f.code"
          />
          <el-input-number
            v-else-if="filterOf(f).widget === 'number'"
            v-model="filterModel[f.code]"
            :controls="false"
            :data-testid="'filter-' + f.code"
          />
          <el-input
            v-else
            v-model="filterModel[f.code]"
            clearable
            :placeholder="'输入' + f.label"
            :data-testid="'filter-' + f.code"
            @keyup.enter="search"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="reset" @click="reset">重置</el-button>
        <el-button type="primary" data-testid="search" @click="search">查询</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="saveOpen" title="保存视图" width="420px" append-to-body data-testid="view-save-dialog">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="视图名称" required>
          <el-input v-model="saveForm.name" maxlength="64" show-word-limit data-testid="view-name" @keyup.enter="saveView" />
        </el-form-item>
        <el-form-item v-if="activeView?.mine">
          <el-checkbox v-model="saveForm.overwrite" data-testid="view-overwrite">覆盖当前视图「{{ activeView.name }}」</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="saveForm.asDefault" data-testid="view-as-default">设为我的默认视图（打开列表时自动应用）</el-checkbox>
        </el-form-item>
        <el-form-item v-if="roles.length" label="共享给角色（可选）">
          <el-select v-model="saveForm.sharedRole" clearable placeholder="不共享" style="width: 100%" data-testid="view-role">
            <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!saveForm.name.trim()" data-testid="view-save-confirm" @click="saveView">保存</el-button>
      </template>
    </el-dialog>

    <div v-if="schema" class="schema-table__toolbar">
      <el-button v-if="schema.permissions.add" type="primary" plain data-testid="create" @click="emit('create')">新增</el-button>
      <el-button v-if="schema.permissions.import" data-testid="import" @click="emit('import')">导入</el-button>
      <el-button v-if="schema.permissions.export" :disabled="busy" data-testid="export" @click="doExport()">导出</el-button>
      <span class="schema-table__grow" />
      <el-popover placement="bottom-end" :width="320" trigger="click">
        <template #reference>
          <el-button data-testid="col-settings" aria-label="列设置">列设置</el-button>
        </template>
        <div class="schema-table__cols" role="group" aria-label="列设置" data-testid="col-panel">
          <div v-for="(c, i) in colState" :key="c.code" class="schema-table__col" :data-testid="'col-' + c.code">
            <el-checkbox
              :model-value="c.visible"
              :aria-label="`显示${labelOf(c.code)}`"
              :data-testid="'col-visible-' + c.code"
              @update:model-value="(v: any) => setCols(patchColumn(colState, c.code, { visible: !!v }))"
              >{{ labelOf(c.code) }}</el-checkbox
            >
            <span class="schema-table__grow" />
            <el-button
              link
              size="small"
              :type="c.fixed === 'left' ? 'primary' : undefined"
              :aria-pressed="c.fixed === 'left'"
              :aria-label="`固定${labelOf(c.code)}到左侧`"
              :data-testid="'col-fix-' + c.code"
              @click="setCols(patchColumn(colState, c.code, { fixed: c.fixed === 'left' ? undefined : 'left' }))"
              >固定</el-button
            >
            <el-button link size="small" :disabled="i === 0" :aria-label="`上移${labelOf(c.code)}`" @click="setCols(moveColumn(colState, c.code, -1))"
              >上移</el-button
            >
            <el-button
              link
              size="small"
              :disabled="i === colState.length - 1"
              :aria-label="`下移${labelOf(c.code)}`"
              @click="setCols(moveColumn(colState, c.code, 1))"
              >下移</el-button
            >
          </div>
          <el-button size="small" data-testid="col-reset" @click="setCols(defaultColumns(allColumns))">恢复默认</el-button>
        </div>
      </el-popover>
    </div>

    <div v-if="updates > 0" class="schema-table__updates" role="status" data-testid="updates-banner">
      有 {{ updates }} 条更新
      <el-button link type="primary" data-testid="updates-refresh" @click="applyUpdates">刷新查看</el-button>
    </div>
    <LsSkeleton v-if="!schema || (loading && !loadedOnce)" variant="table" :rows="6" />
    <VxeTable
      v-else
      ref="grid"
      class="schema-table__grid"
      :data="rows"
      :loading="loading"
      border="inner"
      stripe
      show-overflow="title"
      :max-height="maxHeight"
      :row-config="{ keyField: 'id', isHover: true, isCurrent: true, useKey: true }"
      :column-config="{ resizable: true, drag: true, useKey: true }"
      :column-drag-config="{ trigger: 'cell', showIcon: false, disabledMethod: notDraggable }"
      :virtual-y-config="{ enabled: true, gt: 60, preSize: 4 }"
      :sort-config="{ remote: true, trigger: 'cell' }"
      :checkbox-config="{ highlight: true }"
      :row-group-config="groupBy ? { groupFields: [groupBy], padding: true } : undefined"
      data-testid="table"
      @sort-change="onSort"
      @checkbox-change="syncSelection"
      @checkbox-all="syncSelection"
      @cell-click="onCellClick"
      @cell-dblclick="onCellDblclick"
      @resizable-change="onResize"
      @column-dragend="onDragEnd"
    >
      <VxeColumn v-if="batchOperations.length" type="checkbox" width="44" fixed="left" :resizable="false" />
      <VxeColumn
        v-for="(c, ci) in shownColumns"
        :key="c.field.code"
        :row-group-node="!!groupBy && ci === 0"
        :field="c.field.code"
        :title="c.field.label"
        :sortable="c.field.sortable"
        :width="c.pref.width"
        :min-width="120"
        :fixed="c.pref.fixed"
      >
        <template #default="{ row }">
          <span v-if="isEditing(row, c.field)" class="schema-table__editor" @click.stop @dblclick.stop>
            <el-select
              v-if="c.field.type === 'ENUM'"
              v-model="edit.value"
              size="small"
              automatic-dropdown
              :data-testid="`cell-editor-${c.field.code}-${row.id}`"
              @change="commitEdit(row, c.field)"
              @visible-change="(v: boolean) => !v && commitEdit(row, c.field)"
              @keydown.esc.stop="cancelEdit"
            >
              <el-option v-for="o in c.field.options ?? []" :key="o" :label="c.field.optionLabels?.[o] ?? o" :value="o" />
            </el-select>
            <el-input-number
              v-else-if="c.field.type === 'INTEGER' || c.field.type === 'DECIMAL'"
              v-model="edit.value"
              size="small"
              :controls="false"
              :precision="c.field.type === 'INTEGER' ? 0 : undefined"
              :data-testid="`cell-editor-${c.field.code}-${row.id}`"
              @blur="commitEdit(row, c.field)"
              @keydown.enter.stop="commitEdit(row, c.field)"
              @keydown.esc.stop="cancelEdit"
            />
            <el-date-picker
              v-else-if="c.field.type === 'DATE'"
              v-model="edit.value"
              size="small"
              type="date"
              value-format="YYYY-MM-DD"
              :data-testid="`cell-editor-${c.field.code}-${row.id}`"
              @change="commitEdit(row, c.field)"
              @keydown.esc.stop="cancelEdit"
            />
            <el-input
              v-else
              v-model="edit.value"
              size="small"
              :maxlength="c.field.maxLength"
              :data-testid="`cell-editor-${c.field.code}-${row.id}`"
              @blur="commitEdit(row, c.field)"
              @keydown.enter.stop="commitEdit(row, c.field)"
              @keydown.esc.stop="cancelEdit"
            />
          </span>
          <span
            v-else
            :class="{ 'schema-table__editable': inlineEditable(row, c.field) }"
            :data-testid="inlineEditable(row, c.field) ? `cell-${c.field.code}-${row.id}` : undefined"
            >{{ cellText(c.field, row) }}</span
          >
          <span
            v-if="cellState[cellKey(row, c.field)]"
            :class="['schema-table__mark', 'is-' + cellState[cellKey(row, c.field)].state]"
            :title="cellState[cellKey(row, c.field)].message"
            :data-testid="`cell-state-${c.field.code}-${row.id}`"
            >{{ MARK[cellState[cellKey(row, c.field)].state] }}</span
          >
        </template>
      </VxeColumn>
      <VxeColumn field="status" title="状态" width="110" sortable>
        <template #default="{ row }">{{ stateLabel(row.status) }}</template>
      </VxeColumn>
      <VxeColumn v-if="rowOps.length || schema.permissions.edit" title="操作" fixed="right" min-width="170">
        <template #default="{ row }">
          <template v-for="a in rowActions(row).slice(0, 2)" :key="a.key">
            <el-button link type="primary" :data-testid="a.testId" @click.stop="a.run()">{{ a.label }}</el-button>
          </template>
          <el-dropdown
            v-if="rowActions(row).length > 2"
            trigger="click"
            @command="
              (k: string) =>
                rowActions(row)
                  .find((a) => a.key === k)
                  ?.run()
            "
          >
            <el-button link type="primary" :data-testid="'row-more-' + row.id" @click.stop>更多</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="a in rowActions(row).slice(2)" :key="a.key" :command="a.key" :data-testid="a.testId">{{
                  a.label
                }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </VxeColumn>
    </VxeTable>

    <!-- 批量操作栏：勾选后浮在列表底部 -->
    <div
      v-if="schema && (selection.length || allMatching || batch.running)"
      class="schema-table__batchbar"
      role="region"
      aria-label="批量操作"
      data-testid="batch-bar"
    >
      <span data-testid="batch-count">已选 {{ allMatching ? total : selection.length }} 条{{ allMatching ? '（全部筛选结果）' : '' }}</span>
      <el-button
        v-if="!allMatching && selection.length === rows.length && total > rows.length"
        link
        type="primary"
        data-testid="batch-select-all"
        @click="selectAllMatching"
      >
        选择全部 {{ total }} 条筛选结果
      </el-button>
      <span class="schema-table__grow" />
      <span v-if="batch.running" class="schema-table__batchprog" data-testid="batch-progress"
        >{{ batch.name }}：{{ batch.done }} / {{ batch.items.length }}</span
      >
      <el-button
        v-for="op in batchOperations"
        :key="op.operation"
        size="small"
        type="primary"
        :disabled="batch.running"
        :data-testid="'batch-' + op.operation"
        @click="runBatch(op)"
      >
        批量{{ op.name }}
      </el-button>
      <el-button v-if="schema.permissions.export" size="small" :disabled="busy" data-testid="batch-export" @click="exportSelected"
        >导出所选</el-button
      >
      <el-button size="small" link data-testid="batch-clear" @click="clearSelection">取消选择</el-button>
    </div>

    <!-- 批量执行结果：逐条结果，失败项可单独重试 -->
    <el-dialog
      v-model="batch.visible"
      :title="`批量${batch.name}`"
      width="560px"
      append-to-body
      :close-on-click-modal="false"
      data-testid="batch-dialog"
    >
      <el-progress :percentage="batch.items.length ? Math.round((batch.done / batch.items.length) * 100) : 0" :status="batchStatus" />
      <p data-testid="batch-summary">
        共 {{ batch.items.length }} 条：成功 {{ batchOk }} 条，失败 {{ batchFailed.length }} 条{{ batch.running ? '，执行中…' : '' }}
      </p>
      <el-table v-if="batchFailed.length" :data="batchFailed" size="small" max-height="260" data-testid="batch-failed">
        <el-table-column label="记录" min-width="140">
          <template #default="{ row: it }">{{ it.label }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="220">
          <template #default="{ row: it }">{{ it.message }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button data-testid="batch-close" @click="batch.visible = false">{{ batch.running ? '后台继续' : '关闭' }}</el-button>
        <el-button v-if="!batch.running && batchFailed.length" type="primary" data-testid="batch-retry" @click="retryFailed">
          重试失败项（{{ batchFailed.length }}）
        </el-button>
      </template>
    </el-dialog>

    <el-pagination
      v-if="schema"
      v-model:current-page="page"
      v-model:page-size="size"
      class="schema-table__pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50, 100, 500]"
      @current-change="load"
      @size-change="load"
    />
  </div>
</template>

<script setup lang="ts">
import { notifyDone } from '@/utils/opResult';
import { confirmOperation } from '@/utils/opConfirm';
import { useUndoable } from '@/utils/undoable';
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { VxeColumn, VxeTable } from '@/plugins/vxe';
import { LsSkeleton } from '@/components/LsState';
import { defaultColumns, mergeColumns, moveColumn, patchColumn, reorderVisible, type ColumnPref } from './columnState';
import type { CrudApi } from '@/api/lsmom/crud';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import type { ExportJob, FilterOp, ListSchema, QuerySpec, SchemaField, SchemaOperation } from '@/api/lsmom/types';
import { mapServerErrors } from '@/utils/lsform/serverErrors';
import { buildQuerySpec, cellText, columnsOf, filterOf, rowOperations } from './querySpec';
import type { SavedView, SavedViewApi, ViewContext, ViewDefinition, ViewFilter } from '@/api/lsmom/savedViews';
import {
  KEYWORD,
  statesOf,
  condLabel,
  droppedFields,
  fieldLabel,
  quickChips,
  resolveTokens,
  sameFilter,
  splitFilters,
  type QuickChip
} from './conditions';

/**
 * ListSchema 驱动的通用台账列表（vxe-table 实现，业务页面不直接使用 vxe API）：列、筛选、排序、分页来自服务端白名单；
 * 列可拖拽排序、调整宽度、显隐与固定（列设置由调用方保存，见 columns / columns-change）；行多时虚拟滚动；首次加载显示骨架屏。
 * 行/批量动作只绑定已发布的 Operation（经 /api/op 执行，后端重新校验权限、状态与版本）；导出走异步任务。
 */
const props = withDefaults(
  defineProps<{
    concept: string;
    api?: CrudApi;
    pageSize?: number;
    columns?: ColumnPref[] | null;
    maxHeight?: number | string;
    /** 提供时启用保存视图（筛选＋列＋排序＋分组，个人默认与角色共享） */
    viewApi?: SavedViewApi | null;
    /** 检查他人新增、修改的间隔（毫秒），0 表示不检查 */
    pollInterval?: number;
  }>(),
  { api: () => lazyCrudApi, pageSize: 20, columns: null, maxHeight: 640, viewApi: null, pollInterval: 30000 }
);
const undoable = useUndoable();
const emit = defineEmits<{
  (e: 'create'): void;
  (e: 'edit', row: Record<string, any>): void;
  /** 需要填写输入的行内动作：由调用方打开详情办理 */
  (e: 'row-action', payload: { row: Record<string, any>; operation: string }): void;
  (e: 'row-click', row: Record<string, any>): void;
  (e: 'action-done', payload: { operation: string; ok: number; failed: { id: unknown; message: string }[] }): void;
  (e: 'import'): void;
  (e: 'exported', job: ExportJob): void;
  (e: 'columns-change', cols: ColumnPref[]): void;
  (e: 'columns-dropped', codes: string[]): void;
}>();

const schema = ref<ListSchema | null>(null);
const rows = ref<Record<string, any>[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(props.pageSize);
const loading = ref(false);
const busy = ref(false);
const selection = ref<Record<string, any>[]>([]);
const sort = ref<{ field: string; dir: 'asc' | 'desc' } | null>(null);
const filterModel = reactive<Record<string, any>>({});
const loadedOnce = ref(false);
const grid = ref<any>();
const colState = ref<ColumnPref[]>([]);

// —— 关键字、快速筛选、额外条件、分组 ——
const keyword = ref('');
const extra = ref<ViewFilter[]>([]);
const groupBy = ref<string | null>(null);
const advOpen = ref(false);
const chips = computed<QuickChip[]>(() => (schema.value ? quickChips(schema.value) : []));
const keywordHint = computed(() => {
  const names = (schema.value?.fields ?? []).filter((f) => f.queryable && (f.type === 'STRING' || f.type === 'TEXT')).map((f) => f.label);
  return names.length ? ` ${names.slice(0, 3).join('、')}${names.length > 3 ? ' 等' : ''}` : '';
});
let viewCtx: ViewContext | null = null;
async function context() {
  if (viewCtx || !props.viewApi) return viewCtx;
  viewCtx = await props.viewApi.context().catch(() => null);
  return viewCtx;
}
const chipOn = (c: QuickChip) => c.filters.every((f) => extra.value.some((x) => sameFilter(x, f)));
function toggleChip(c: QuickChip) {
  extra.value = chipOn(c) ? extra.value.filter((x) => !c.filters.some((f) => sameFilter(f, x))) : [...extra.value, ...c.filters];
  void search();
}
/** 筛选表单里的条件（保存视图与标签用，取值未解析） */
const formFilters = (): ViewFilter[] => (schema.value ? (buildQuerySpec(schema.value, filterModel, null, 1, 1).filters as ViewFilter[]) : []);
const formCount = computed(() => {
  void Object.values(filterModel);
  return formFilters().length;
});

interface CondTag {
  key: string;
  field: string;
  label: string;
  kind: 'form' | 'extra' | 'keyword' | 'group';
  filter?: ViewFilter;
}
const tags = computed<CondTag[]>(() => {
  const sch = schema.value;
  if (!sch) return [];
  void Object.values(filterModel);
  const out: CondTag[] = formFilters().map((f) => ({ key: 'form-' + f.field, field: f.field, label: condLabel(sch, f), kind: 'form' }));
  extra.value.forEach((f, i) => out.push({ key: `extra-${i}`, field: f.field, label: condLabel(sch, f, chips.value), kind: 'extra', filter: f }));
  if (keyword.value.trim()) out.push({ key: 'kw', field: KEYWORD, label: `关键字：${keyword.value.trim()}`, kind: 'keyword' });
  if (groupBy.value) out.push({ key: 'group', field: 'groupBy', label: `按${fieldLabel(sch, groupBy.value)}分组`, kind: 'group' });
  return out;
});
function removeTag(t: CondTag) {
  if (t.kind === 'form') delete filterModel[t.field];
  else if (t.kind === 'extra') extra.value = extra.value.filter((x) => x !== t.filter);
  else if (t.kind === 'keyword') keyword.value = '';
  else groupBy.value = null;
  void search();
}

// —— 保存视图 ——
const views = ref<SavedView[]>([]);
const defaultId = ref<number | null>(null);
const activeViewId = ref<number | null>(null);
const activeView = computed(() => views.value.find((v) => v.id === activeViewId.value) ?? null);
const roles = ref<{ roleKey: string; roleName: string }[]>([]);
const saveOpen = ref(false);
const saveForm = reactive({ name: '', overwrite: false, asDefault: false, sharedRole: null as string | null });
const viewLabel = (v: SavedView) => `${v.name}${v.mine ? '' : '（共享）'}${v.id === defaultId.value ? ' · 默认' : ''}`;

function currentDefinition(): ViewDefinition {
  const kw = keyword.value.trim();
  return {
    filters: [...formFilters(), ...extra.value],
    columns: colState.value,
    sort: sort.value ? [sort.value] : [],
    groupBy: groupBy.value,
    ...(kw ? { keyword: kw } : {})
  };
}

/** 套用视图：已弃用的字段忽略并提示 */
function applyDefinition(def: ViewDefinition | null, name?: string) {
  const sch = schema.value!;
  Object.keys(filterModel).forEach((k) => delete filterModel[k]);
  keyword.value = '';
  const split = splitFilters(sch, def?.filters ?? []);
  Object.assign(filterModel, split.model);
  keyword.value = def?.keyword ?? '';
  extra.value = split.extra.filter((f) => f.field !== KEYWORD);
  const r = mergeColumns(allColumns.value, def?.columns);
  colState.value = r.columns;
  const okSort = (def?.sort ?? []).find(
    (x) => sch.fields.some((f) => f.code === x.field && f.sortable) || ['id', 'status', 'createdAt', 'updatedAt'].includes(x.field)
  );
  sort.value = okSort ? { field: okSort.field, dir: okSort.dir } : null;
  const g = def?.groupBy && droppedFields(sch, [def.groupBy]).length === 0 ? def.groupBy : null;
  groupBy.value = g;
  const dropped = [...new Set([...split.dropped, ...r.dropped, ...droppedFields(sch, [...(def?.sort ?? []).map((x) => x.field), def?.groupBy])])];
  if (dropped.length) {
    emit('columns-dropped', dropped);
    ElMessage.warning(`视图${name ? `「${name}」` : ''}中的字段 ${dropped.join('、')} 已停用，已忽略`);
  }
}

async function loadViews() {
  if (!props.viewApi) return;
  const r = await props.viewApi.list(props.concept).catch(() => ({ views: [], defaultId: null }));
  views.value = r.views;
  defaultId.value = r.defaultId;
}

async function onViewChange(id: number | null) {
  const v = views.value.find((x) => x.id === id);
  applyDefinition(v?.definition ?? null, v?.name);
  await search();
}

function openSave() {
  const v = activeView.value;
  Object.assign(saveForm, {
    name: v?.mine ? v.name : '',
    overwrite: !!v?.mine,
    asDefault: v ? v.id === defaultId.value : false,
    sharedRole: v?.mine ? (v.sharedRole ?? null) : null
  });
  saveOpen.value = true;
  if (props.viewApi && !roles.value.length) void props.viewApi.roles().then((r) => (roles.value = r));
}

async function saveView() {
  const api = props.viewApi;
  const name = saveForm.name.trim();
  if (!api || !name) return;
  const body = { name, definition: currentDefinition(), sharedRole: saveForm.sharedRole || null };
  let id: number;
  if (saveForm.overwrite && activeView.value?.mine) {
    id = activeView.value.id;
    await api.update(id, body);
  } else {
    id = (await api.create({ concept: props.concept, ...body })).id;
  }
  if (saveForm.asDefault) await api.setDefault(props.concept, id);
  else if (defaultId.value === id) await api.setDefault(props.concept, null);
  await loadViews();
  activeViewId.value = id;
  saveOpen.value = false;
  ElMessage.success(saveForm.asDefault ? `已保存视图「${name}」并设为默认` : `已保存视图「${name}」`);
}

async function toggleDefault() {
  const v = activeView.value;
  if (!v || !props.viewApi) return;
  const on = v.id !== defaultId.value;
  await props.viewApi.setDefault(props.concept, on ? v.id : null);
  defaultId.value = on ? v.id : null;
  ElMessage.success(on ? `「${v.name}」已设为默认视图` : '已取消默认视图');
}

async function deleteView() {
  const v = activeView.value;
  if (!v?.mine || !props.viewApi) return;
  await props.viewApi.remove(v.id);
  activeViewId.value = null;
  await loadViews();
  ElMessage.success(`已删除视图「${v.name}」`);
}

const filterFields = computed(() => (schema.value ? schema.value.fields.filter((f) => f.queryable) : []));
const allColumns = computed(() => (schema.value ? columnsOf(schema.value) : []));
const fieldOf = (code: string) => allColumns.value.find((f) => f.code === code);
const labelOf = (code: string) => fieldOf(code)?.label ?? code;
const shownColumns = computed(() => colState.value.filter((c) => c.visible && fieldOf(c.code)).map((c) => ({ pref: c, field: fieldOf(c.code)! })));

/** 套用调用方给的列设置（如保存视图）；弃用字段忽略并告知调用方。 */
function applyColumns(saved: ColumnPref[] | null | undefined) {
  const r = mergeColumns(allColumns.value, saved);
  colState.value = r.columns;
  if (r.dropped.length) emit('columns-dropped', r.dropped);
}
function setCols(next: ColumnPref[]) {
  colState.value = next;
  emit('columns-change', next);
}
watch(
  () => props.columns,
  (c) => schema.value && applyColumns(c)
);

function onResize({ column, resizeWidth }: any) {
  if (column?.field && fieldOf(column.field))
    setCols(patchColumn(colState.value, column.field, { width: Math.round(resizeWidth ?? column.renderWidth) }));
}
/** 只有业务字段列可拖拽（勾选、状态、操作列位置固定） */
const notDraggable = ({ column }: any) => !column?.field || !fieldOf(column.field);
function onDragEnd() {
  const order = ((grid.value?.getColumns?.() ?? []) as any[]).map((c) => c.field).filter((f: string) => f && fieldOf(f));
  setCols(reorderVisible(colState.value, order));
}
function syncSelection() {
  selection.value = grid.value?.getCheckboxRecords?.() ?? [];
}
let clickTimer: ReturnType<typeof setTimeout> | null = null;
function onCellClick({ row, column }: any) {
  if (!column || column.type === 'checkbox' || column.title === '操作') return;
  if (isEditing(row, { code: column.field } as SchemaField)) return;
  const f = allColumns.value.find((x) => x.code === column.field);
  // 可行内编辑的单元格：等一下看是不是双击，双击就编辑而不打开详情
  if (f && inlineEditable(row, f)) {
    if (clickTimer) clearTimeout(clickTimer);
    clickTimer = setTimeout(() => {
      clickTimer = null;
      emit('row-click', row);
    }, 260);
    return;
  }
  emit('row-click', row);
}
const rowOps = computed(() => (schema.value ? rowOperations(schema.value) : []));
const batchOperations = computed(() => rowOps.value.filter((o) => !hasRequiredInput(o)));

function hasRequiredInput(op: SchemaOperation) {
  const req = (op.inputSchema as any)?.required;
  return Array.isArray(req) && req.length > 0;
}

// ---------- 行内编辑（只对草稿，字段可写且服务端字段策略允许；失焦或 Enter 保存，Esc 取消） ----------
const INLINE_TYPES = new Set(['STRING', 'TEXT', 'INTEGER', 'DECIMAL', 'DATE', 'ENUM']);
const MARK: Record<string, string> = { saving: '保存中…', saved: '✓ 已保存', error: '! 未保存' };
const edit = reactive<{ id: unknown; field: string; value: any; saving: boolean }>({ id: null, field: '', value: null, saving: false });
const cellState = reactive<Record<string, { state: 'saving' | 'saved' | 'error'; message?: string }>>({});
const initialState = computed(() => (schema.value && statesOf(schema.value).find((x) => x.initial)?.code) || 'DRAFT');
const newKey = () => (globalThis.crypto?.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`);
const cellKey = (row: Record<string, any>, f: SchemaField) => `${row.id}:${f.code}`;

function inlineEditable(row: Record<string, any>, f: SchemaField) {
  const sch = schema.value;
  return !!sch?.permissions.edit && row.status === initialState.value && f.writable && INLINE_TYPES.has(f.type) && (f as any).inlineEdit !== false;
}
function isEditing(row: Record<string, any>, f: SchemaField) {
  return edit.id === row.id && edit.field === f.code;
}
function onCellDblclick({ row, column }: any) {
  if (clickTimer) {
    clearTimeout(clickTimer);
    clickTimer = null;
  }
  const f = allColumns.value.find((x) => x.code === column?.field);
  if (!f || !inlineEditable(row, f) || edit.saving) return;
  Object.assign(edit, { id: row.id, field: f.code, value: row[f.code] ?? (f.type === 'INTEGER' || f.type === 'DECIMAL' ? undefined : '') });
  void nextTick(() => {
    const el = document.querySelector<HTMLElement>(`[data-testid="cell-editor-${f.code}-${row.id}"]`);
    (el?.tagName === 'INPUT' ? el : el?.querySelector<HTMLElement>('input'))?.focus();
  });
}
function cancelEdit() {
  Object.assign(edit, { id: null, field: '', value: null });
}
async function commitEdit(row: Record<string, any>, f: SchemaField) {
  if (!isEditing(row, f) || edit.saving) return;
  const value = edit.value === '' && f.type !== 'STRING' && f.type !== 'TEXT' ? null : (edit.value ?? null);
  if (value === (row[f.code] ?? null) || (value === '' && row[f.code] == null)) return cancelEdit();
  const key = cellKey(row, f);
  edit.saving = true;
  cellState[key] = { state: 'saving' };
  try {
    const r = await props.api.update(props.concept, row.id, Number(row.version), { [f.code]: value }, newKey());
    row[f.code] = value;
    delete row[f.code + 'Label'];
    row.version = r.version;
    cellState[key] = { state: 'saved' };
    setTimeout(() => cellState[key]?.state === 'saved' && delete cellState[key], 2000);
    cancelEdit();
  } catch (e: any) {
    // 服务端拒绝：保留原值，单元格标出原因
    const mapped = mapServerErrors(e);
    cellState[key] = { state: 'error', message: mapped?.errors[f.code] || e?.message || '保存失败' };
    cancelEdit();
  } finally {
    edit.saving = false;
  }
}

// ---------- 行内动作：当前状态下可执行的动作，前 2 个直接显示，其余进"更多" ----------
interface RowAction {
  key: string;
  label: string;
  testId: string;
  run: () => void;
}
function rowActions(row: Record<string, any>): RowAction[] {
  const sch = schema.value;
  if (!sch) return [];
  const out: RowAction[] = [];
  if (sch.permissions.edit && row.status === initialState.value) {
    out.push({ key: 'edit', label: '编辑', testId: 'row-edit-' + row.id, run: () => emit('edit', row) });
  }
  for (const op of rowOps.value.filter((o) => allowedIn(o, row))) {
    out.push({
      key: op.operation,
      label: op.name,
      testId: 'op-' + op.operation + '-' + row.id,
      // 需要填写输入的动作在详情抽屉里办理
      run: () => (hasRequiredInput(op) ? emit('row-action', { row, operation: op.operation }) : runRow(op, row))
    });
  }
  return out;
}

/** 状态显示名（来自状态机），没有状态机时显示原值 */
function stateLabel(code: string) {
  return (schema.value && statesOf(schema.value).find((x) => x.code === code)?.label) || code;
}

function allowedIn(op: SchemaOperation & { from?: string[] }, row: Record<string, any>) {
  return !op.from || op.from.includes(row.status);
}

function spec() {
  const sp = buildQuerySpec(schema.value!, filterModel, sort.value, page.value, size.value);
  sp.filters = [...sp.filters, ...(resolveTokens(extra.value, viewCtx) as typeof sp.filters)];
  if (keyword.value.trim()) sp.filters.push({ field: KEYWORD, op: 'like', value: keyword.value.trim() });
  // 分组：先按分组字段排序，同组的行相邻
  if (groupBy.value) sp.sort = [{ field: groupBy.value, dir: 'asc' }, ...sp.sort.filter((x) => x.field !== groupBy.value)];
  return sp;
}

async function load() {
  if (!schema.value) return;
  loading.value = true;
  if (extra.value.some((f) => typeof f.value === 'string' && f.value.startsWith('$context.'))) await context();
  try {
    const p = await props.api.query(props.concept, spec());
    rows.value = p.rows as Record<string, any>[];
    total.value = p.total;
    selection.value = [];
    allMatching.value = false;
  } finally {
    loading.value = false;
    loadedOnce.value = true;
  }
  void markBaseline();
}

// ---------- "有 N 条更新"：按当前筛选条件轮询，新增或修改时提示，不自动刷新（不打乱滚动与勾选） ----------
const updates = ref(0);
let baseline: string | null = null;
let baselineOffset = 0;
let pollTimer: ReturnType<typeof setInterval> | null = null;
const MAX_U: NonNullable<QuerySpec['aggregates']> = [
  { fn: 'max', field: 'updatedAt', alias: 'u' },
  { fn: 'max', field: 'createdAt', alias: 'c' }
];
const pad = (n: number) => String(n).padStart(2, '0');
const nowText = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};
const asText = (v: unknown) => (v == null ? null : String(v).replace(' ', 'T'));

function changedSince(): QuerySpec {
  const base = spec();
  return {
    ...base,
    page: 1,
    size: 1,
    sort: [],
    filters: [...base.filters, { field: 'updatedAt', op: 'gt', value: baseline }],
    aggregates: [{ fn: 'count', field: '*', alias: 'n' }]
  };
}
async function countChanged() {
  const r = await props.api.aggregate(props.concept, changedSince());
  return Number(r[0]?.n ?? 0);
}
/** 记下当前结果里最新的修改时间；时间精度到秒时同一秒内的记录先记为基数，避免刚加载就提示 */
async function markBaseline() {
  updates.value = 0;
  if (!props.pollInterval || typeof props.api.aggregate !== 'function') return;
  try {
    const base = spec();
    const r = await props.api.aggregate(props.concept, { ...base, page: 1, size: 1, sort: [], aggregates: MAX_U });
    const marks = [asText(r[0]?.u), asText(r[0]?.c)].filter((x): x is string => !!x).sort();
    baseline = marks.at(-1) ?? nowText();
    baselineOffset = await countChanged();
  } catch {
    baseline = null;
  }
}
async function poll() {
  if (!baseline || loading.value || batch.running || document.hidden) return;
  try {
    updates.value = Math.max(0, (await countChanged()) - baselineOffset);
  } catch {
    /* 轮询失败不打扰用户，下次再试 */
  }
}
function startPolling() {
  stopPolling();
  if (props.pollInterval > 0) pollTimer = setInterval(poll, props.pollInterval);
}
function stopPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}
/** 刷新到最新：保留勾选与滚动位置 */
async function applyUpdates() {
  const keep = new Set(selection.value.map((r) => r.id));
  const pos = grid.value?.getScroll?.();
  await load();
  const again = rows.value.filter((r) => keep.has(r.id));
  if (again.length) {
    await nextTick();
    await grid.value?.setCheckboxRow?.(again, true);
    selection.value = again;
  }
  if (pos) {
    await nextTick();
    await grid.value?.scrollTo?.(pos.scrollLeft, pos.scrollTop);
  }
}

function search() {
  page.value = 1;
  advOpen.value = false;
  return load();
}

function reset() {
  Object.keys(filterModel).forEach((k) => delete filterModel[k]);
  extra.value = [];
  keyword.value = '';
  groupBy.value = null;
  sort.value = null;
  grid.value?.clearSort?.();
  return search();
}

function onSort({ field, order }: any) {
  sort.value = order ? { field, dir: order } : null;
  return load();
}

/** 不可撤销的动作先确认：列出影响对象、状态变化与后果 */
function confirmRow(op: SchemaOperation, row: Record<string, any>) {
  return confirmOperation({
    name: op.name,
    label: rowLabel(row),
    impact: typeof props.api.impact === 'function' ? () => props.api.impact(op.operation, row.id) : undefined,
    stateLabel: (c) => stateLabel(c ?? '')
  });
}

async function runRow(op: SchemaOperation, row: Record<string, any>) {
  if (op.undoable) return runRowUndoable(op, row);
  if (!(await confirmRow(op, row))) return;
  busy.value = true;
  try {
    const r = await props.api.execute(op.operation, { targetId: String(row.id), version: Number(row.version), input: {} });
    if (r.warnings?.length) notifyDone(`已${op.name}`, r.warnings);
    emit('action-done', { operation: op.operation, ok: 1, failed: [] });
  } catch (e: any) {
    emit('action-done', { operation: op.operation, ok: 0, failed: [{ id: row.id, message: e?.message ?? String(e) }] });
  } finally {
    busy.value = false;
    await load();
  }
}

/** 可撤销的动作：直接执行，提示里 5 秒内可撤销 */
async function runRowUndoable(op: SchemaOperation, row: Record<string, any>) {
  busy.value = true;
  try {
    await undoable.run({
      done: `已${op.name}`,
      exec: () => props.api.execute(op.operation, { targetId: String(row.id), version: Number(row.version), input: {} }),
      compensate: (o, id, version, input) => props.api.execute(o, { targetId: id, version, input }),
      after: load
    });
    emit('action-done', { operation: op.operation, ok: 1, failed: [] });
  } catch (e: any) {
    emit('action-done', { operation: op.operation, ok: 0, failed: [{ id: row.id, message: e?.message ?? String(e) }] });
  } finally {
    busy.value = false;
    await load();
  }
}

// ---------- 批量操作：逐条独立执行（每条独立校验、独立事务），并发 4，逐条结果，失败项可重试 ----------
interface BatchItem {
  id: unknown;
  version: number;
  status: string;
  label: string;
  state: 'pending' | 'ok' | 'failed';
  message?: string;
}
const MAX_BATCH = 5000;
const allMatching = ref(false);
const batch = reactive({ visible: false, running: false, name: '', op: null as SchemaOperation | null, items: [] as BatchItem[], done: 0 });
const batchOk = computed(() => batch.items.filter((i) => i.state === 'ok').length);
const batchFailed = computed(() => batch.items.filter((i) => i.state === 'failed'));
const batchStatus = computed(() => (batch.running ? undefined : batchFailed.value.length ? 'warning' : 'success'));

function rowLabel(r: Record<string, any>) {
  const f = allColumns.value.find((x) => x.type === 'STRING' && r[x.code]);
  return f ? `#${r.id} ${r[f.code]}` : `#${r.id}`;
}

async function selectAllMatching() {
  const n = total.value;
  if (n > MAX_BATCH) {
    ElMessage.warning(`筛选结果有 ${n} 条，一次最多 ${MAX_BATCH} 条，请先缩小筛选范围`);
    return;
  }
  try {
    await ElMessageBox.confirm(`将选择全部 ${n} 条筛选结果（包括其他页），之后的批量动作会对这 ${n} 条执行。确定选择？`, '选择全部筛选结果', {
      confirmButtonText: `选择 ${n} 条`,
      cancelButtonText: '取消',
      type: 'warning'
    });
  } catch {
    return;
  }
  allMatching.value = true;
}

function clearSelection() {
  allMatching.value = false;
  grid.value?.clearCheckboxRow?.();
  selection.value = [];
}

/** 批量的目标：本页勾选，或全部筛选结果（分页取齐） */
async function batchTargets(): Promise<Record<string, any>[]> {
  if (!allMatching.value) return [...selection.value];
  const out: Record<string, any>[] = [];
  const base = spec();
  for (let p = 1; out.length < Math.min(total.value, MAX_BATCH); p++) {
    const r = await props.api.query(props.concept, { ...base, page: p, size: 500 });
    out.push(...(r.rows as Record<string, any>[]));
    if (r.rows.length < 500) break;
  }
  return out;
}

async function execItems(op: SchemaOperation, items: BatchItem[]) {
  batch.running = true;
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const it = items[next++];
      try {
        const r = await props.api.execute(op.operation, { targetId: String(it.id), version: it.version, input: {} });
        Object.assign(it, { state: 'ok', message: undefined, version: r.version, status: r.status });
      } catch (e: any) {
        Object.assign(it, { state: 'failed', message: e?.message ?? String(e) });
      }
      batch.done++;
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, items.length) }, worker));
  batch.running = false;
  const failed = batchFailed.value;
  const ok = batchOk.value;
  if (failed.length) ElMessage.warning(`${op.name}：成功 ${ok} 条，失败 ${failed.length} 条`);
  else ElMessage.success(`${op.name}：成功 ${ok} 条`);
  emit('action-done', { operation: op.operation, ok, failed: failed.map((f) => ({ id: f.id, message: f.message ?? '' })) });
  await load();
}

async function runBatch(op: SchemaOperation) {
  const rowsToRun = await batchTargets();
  if (allMatching.value || rowsToRun.length > 50) {
    try {
      await ElMessageBox.confirm(
        `将对 ${rowsToRun.length} 条记录执行「${op.name}」，每条单独校验，失败的不影响其他条。确定执行？`,
        `批量${op.name}`,
        {
          confirmButtonText: '执行',
          cancelButtonText: '取消',
          type: 'warning'
        }
      );
    } catch {
      return;
    }
  }
  const items: BatchItem[] = rowsToRun.map((r) => ({
    id: r.id,
    version: Number(r.version),
    status: r.status,
    label: rowLabel(r),
    // 当前状态不允许的直接记为失败，不发请求
    ...(allowedIn(op, r) ? { state: 'pending' as const } : { state: 'failed' as const, message: `当前状态（${stateLabel(r.status)}）不能${op.name}` })
  }));
  Object.assign(batch, { visible: true, name: op.name, op, items, done: items.filter((i) => i.state === 'failed').length });
  clearSelection();
  // 用响应式的 batch.items，逐条结果才会实时显示
  await execItems(
    op,
    batch.items.filter((i) => i.state === 'pending')
  );
}

/** 只重试失败项：先取最新版本与状态，状态已不允许的不再执行 */
async function retryFailed() {
  const op = batch.op;
  if (!op) return;
  const failed = batchFailed.value;
  batch.done -= failed.length;
  for (const it of failed) {
    try {
      const cur = (await props.api.get(props.concept, it.id as number)) as Record<string, any>;
      Object.assign(it, { version: Number(cur.version), status: cur.status, state: 'pending', message: undefined });
      if (!allowedIn(op, cur)) {
        Object.assign(it, { state: 'failed', message: `当前状态（${stateLabel(cur.status)}）不能${op.name}` });
        batch.done++;
      }
    } catch (e: any) {
      Object.assign(it, { state: 'failed', message: e?.message ?? String(e) });
      batch.done++;
    }
  }
  await execItems(
    op,
    failed.filter((i) => i.state === 'pending')
  );
}

/** 导出所选：全部筛选结果按当前条件导出；本页勾选按编号导出。都是异步任务。 */
async function exportSelected() {
  if (allMatching.value) {
    try {
      await ElMessageBox.confirm(`将导出全部 ${total.value} 条筛选结果，导出在后台进行，完成后可下载。确定导出？`, '导出', { type: 'info' });
    } catch {
      return;
    }
    return doExport();
  }
  const ids = selection.value.map((r) => r.id);
  return doExport([{ field: 'id', op: 'in', value: ids }]);
}

async function doExport(more: { field: string; op: FilterOp; value?: unknown }[] = []) {
  busy.value = true;
  try {
    const base = spec();
    const job = await props.api.exportJob(props.concept, { ...base, filters: [...base.filters, ...more], page: 1 });
    ElMessage.success(`已提交导出任务 #${job.id}`);
    emit('exported', job);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  schema.value = await props.api.schema(props.concept);
  applyColumns(props.columns);
  // 默认视图：打开列表时自动应用
  await loadViews();
  const def = views.value.find((v) => v.id === defaultId.value);
  if (def) {
    activeViewId.value = def.id;
    applyDefinition(def.definition, def.name);
  }
  await load();
  if (sort.value) grid.value?.setSort?.({ field: sort.value.field, order: sort.value.dir });
  startPolling();
});
onBeforeUnmount(stopPolling);
onActivated(() => {
  if (schema.value) {
    void poll();
    startPolling();
  }
});
onDeactivated(stopPolling);

/** 高亮某一行（抽屉里切换上一条/下一条时跟随），不重新加载 */
function setCurrent(id: unknown) {
  const row = rows.value.find((r) => r.id === id);
  if (row) void grid.value?.setCurrentRow?.(row);
}

defineExpose({
  load,
  search,
  reset,
  spec,
  columnState: () => colState.value,
  currentDefinition,
  applyDefinition,
  rows: () => rows.value,
  schema: () => schema.value,
  setCurrent
});
</script>

<style scoped>
.schema-table__search {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.schema-table__search > .el-button + .el-button {
  margin-left: 0;
}
.schema-table__keyword {
  width: 260px;
}
.schema-table__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.schema-table__conds {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.schema-table__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.schema-table__toolbar > .el-button + .el-button {
  margin-left: 0;
}
.schema-table__grow {
  flex: 1;
}
.schema-table__cols {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 360px;
  overflow: auto;
}
.schema-table__col {
  display: flex;
  align-items: center;
  gap: 4px;
}
.schema-table__col .el-button + .el-button {
  margin-left: 0;
}
.schema-table__editable {
  cursor: text;
  border-bottom: 1px dashed var(--el-border-color);
}
.schema-table__editor {
  display: inline-block;
  width: 100%;
}
.schema-table__mark {
  margin-left: 6px;
  font-size: 12px;
}
.schema-table__mark.is-saving {
  color: var(--el-text-color-secondary);
}
.schema-table__mark.is-saved {
  color: var(--el-color-success);
}
.schema-table__mark.is-error {
  color: var(--el-color-danger);
  cursor: help;
}
.schema-table__updates {
  margin-bottom: 8px;
  padding: 4px 12px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
  font-size: 13px;
}
.schema-table__batchbar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
  padding: 8px 12px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
}
.schema-table__batchprog {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.schema-table__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>

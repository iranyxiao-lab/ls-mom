<template>
  <div data-testid="agent-admin">
    <div class="bar">
      <el-button type="primary" data-testid="btn-new-agent" @click="openCreate">新建 Agent</el-button>
      <span class="sub"
        >Agent 代真人行动：有效权限 = 真人权限 ∩ Agent 权限；只能调用暴露给 Agent 的能力，代审批、双签、质量终判、删除、DCS 写入永不暴露。</span
      >
    </div>
    <el-table v-loading="loading" :data="agents" data-testid="table-agents" @row-click="(r: AgentIdentity) => open(r.id)">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="code" label="编码" min-width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权限上限" min-width="240">
        <template #default="{ row }">{{ row.permissions.join('、') }}</template>
      </el-table-column>
      <el-table-column prop="rateLimitPerMinute" label="限流（次/分）" width="110" />
      <el-table-column label="有效 Key" width="90">
        <template #default="{ row }">{{ activeKeys(row).length }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新建 Agent" width="560px" data-testid="dlg-create">
      <el-form label-width="110px">
        <el-form-item label="编码"><el-input v-model="form.code" placeholder="小写字母、数字、连字符" data-testid="in-code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" data-testid="in-name" /></el-form-item>
        <el-form-item label="权限上限">
          <el-input v-model="form.perms" type="textarea" :rows="4" placeholder="每行一个权限点，如 lsmom:issue:raise" data-testid="in-perms" />
        </el-form-item>
        <el-form-item label="限流（次/分）"><el-input-number v-model="form.rate" :min="1" :max="10000" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" data-testid="btn-create" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="keyOpen" title="API Key（只显示这一次）" width="620px" data-testid="dlg-key" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" title="请立即复制并妥善保存。系统只保存摘要，关闭后无法再次查看。" class="mb" />
      <el-input :model-value="issued?.key" readonly data-testid="issued-key" />
      <div class="sub mt">前缀 {{ issued?.info.prefix }}，有效期至 {{ fmt(issued?.info.expiresAt) }}</div>
      <template #footer>
        <el-button type="primary" data-testid="btn-key-done" @click="closeKey">我已保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" size="64%" :title="cur ? `${cur.name}（${cur.code}）` : ''" data-testid="agent-drawer" destroy-on-close>
      <template v-if="cur">
        <div class="bar">
          <el-button v-if="cur.status === 'ACTIVE'" data-testid="btn-disable" @click="toggle(false)">停用</el-button>
          <el-button v-else type="success" data-testid="btn-enable" @click="toggle(true)">启用</el-button>
          <el-button type="primary" data-testid="btn-issue" @click="issue">签发 Key</el-button>
        </div>
        <el-tabs v-model="detailTab">
          <el-tab-pane label="API Key" name="keys">
            <el-table :data="cur.keys" size="small" data-testid="table-keys">
              <el-table-column prop="prefix" label="前缀" width="130" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="keyState(row).type">{{ keyState(row).text }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="有效期至" width="140"
                ><template #default="{ row }">{{ fmt(row.expiresAt) }}</template></el-table-column
              >
              <el-table-column label="最近使用" width="140"
                ><template #default="{ row }">{{ fmt(row.lastUsedAt) }}</template></el-table-column
              >
              <el-table-column prop="revokeReason" label="吊销原因" min-width="120" />
              <el-table-column label="操作" width="150">
                <template #default="{ row }">
                  <template v-if="keyState(row).text === '有效'">
                    <el-button link type="primary" :data-testid="`btn-rotate-${row.prefix}`" @click="rotate(row)">轮换</el-button>
                    <el-button link type="danger" :data-testid="`btn-revoke-${row.prefix}`" @click="revoke(row)">吊销</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="权限" name="perms">
            <el-input v-model="permText" type="textarea" :rows="6" data-testid="perm-text" />
            <div class="bar mt">
              <span>限流</span><el-input-number v-model="rate" :min="1" :max="10000" />
              <el-button type="primary" data-testid="btn-save-perms" @click="savePerms">保存</el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane label="调用审计" name="audit">
            <div class="bar">
              <el-input v-model="batch" placeholder="按批次号筛选" style="width: 240px" clearable />
              <el-button @click="loadAudit">查询</el-button>
            </div>
            <el-table :data="audit" size="small" data-testid="table-audit">
              <el-table-column label="时间" width="140"
                ><template #default="{ row }">{{ fmt(row.occurred_at) }}</template></el-table-column
              >
              <el-table-column prop="op_code" label="能力" min-width="200" />
              <el-table-column label="主体" min-width="180">
                <template #default="{ row }">{{ row.agent_name }}（代 {{ row.on_behalf_of_name ?? row.on_behalf_of }}）</template>
              </el-table-column>
              <el-table-column label="结果" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : row.result === 'FAILED' ? 'danger' : 'info'">{{
                    RESULT[row.result] ?? row.result
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="说明" min-width="200">
                <template #default="{ row }">{{
                  row.error_code ? `${row.error_code}：${row.error_message}` : row.target_id ? `${row.target_concept} #${row.target_id}` : ''
                }}</template>
              </el-table-column>
              <el-table-column prop="batch_id" label="批次" width="120" />
              <el-table-column prop="trace_id" label="trace_id" width="140" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="密钥事件" name="events">
            <el-table :data="cur.events ?? []" size="small" data-testid="table-events">
              <el-table-column label="时间" width="140"
                ><template #default="{ row }">{{ fmt(row.occurred_at) }}</template></el-table-column
              >
              <el-table-column label="事件" width="120"
                ><template #default="{ row }">{{ EVENT[row.event] ?? row.event }}</template></el-table-column
              >
              <el-table-column prop="prefix" label="前缀" width="130" />
              <el-table-column prop="detail" label="说明" min-width="200" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { AgentApi, AgentAuditRow, AgentIdentity, AgentKey, IssuedKey } from '@/api/lsmom/agent';

/** Agent 管理：服务身份（权限上限、限流）、API Key 签发 / 轮换 / 吊销、OBO 双主体调用审计、密钥事件。 */
const props = defineProps<{ api: AgentApi; now?: () => Date }>();

const RESULT: Record<string, string> = { SUCCESS: '成功', FAILED: '拒绝/失败', SIMULATED: '模拟', REPLAYED: '重放' };
const EVENT: Record<string, string> = {
  ISSUED: '签发',
  ROTATED: '轮换',
  REVOKED: '吊销',
  INVALID: '无效 Key',
  EXPIRED_USE: '过期使用',
  REVOKED_USE: '吊销后使用',
  RATE_LIMITED: '限流',
  DISABLED_USE: '停用后使用'
};

const loading = ref(false);
const agents = ref<AgentIdentity[]>([]);
const cur = ref<AgentIdentity>();
const drawer = ref(false);
const detailTab = ref('keys');
const createOpen = ref(false);
const keyOpen = ref(false);
const issued = ref<IssuedKey>();
const audit = ref<AgentAuditRow[]>([]);
const batch = ref('');
const permText = ref('');
const rate = ref(60);
const form = reactive({ code: '', name: '', perms: '', rate: 60, description: '' });

const now = () => (props.now ? props.now() : new Date());
const fmt = (s?: string) => (s ? s.replace('T', ' ').substring(0, 16) : '');
const perms = (t: string) =>
  t
    .split(/[\s,，]+/)
    .map((x) => x.trim())
    .filter(Boolean);
const keyState = (k: AgentKey): { text: string; type: 'success' | 'info' | 'danger' | 'warning' } => {
  if (k.status === 'REVOKED') return { text: '已吊销', type: 'danger' };
  if (new Date(k.expiresAt) <= now()) return { text: '已过期', type: 'info' };
  if (k.rotatedTo) return { text: '宽限中', type: 'warning' };
  return { text: '有效', type: 'success' };
};
const activeKeys = (a: AgentIdentity) => a.keys.filter((k) => keyState(k).text !== '已吊销' && keyState(k).text !== '已过期');

async function load() {
  loading.value = true;
  try {
    agents.value = await props.api.list();
  } finally {
    loading.value = false;
  }
}

async function open(id: number) {
  cur.value = await props.api.get(id);
  permText.value = cur.value.permissions.join('\n');
  rate.value = cur.value.rateLimitPerMinute;
  audit.value = await props.api.audit(id);
  drawer.value = true;
}

async function refresh() {
  if (cur.value) await open(cur.value.id);
  await load();
}

function openCreate() {
  Object.assign(form, { code: '', name: '', perms: '', rate: 60, description: '' });
  createOpen.value = true;
}

async function create() {
  const a = await props.api.create({
    code: form.code.trim(),
    name: form.name.trim(),
    permissions: perms(form.perms),
    rateLimitPerMinute: form.rate,
    description: form.description || undefined
  });
  createOpen.value = false;
  ElMessage.success(`已创建 ${a.name}`);
  await load();
  await open(a.id);
}

async function issue() {
  if (!cur.value) return;
  issued.value = await props.api.issueKey(cur.value.id, 90);
  keyOpen.value = true;
}

async function rotate(k: AgentKey) {
  issued.value = await props.api.rotateKey(k.id, 24);
  keyOpen.value = true;
}

async function revoke(k: AgentKey) {
  const { value } = await ElMessageBox.prompt(`吊销 Key ${k.prefix} 后立即失效，填写原因：`, '吊销 Key', { inputValue: '' });
  await props.api.revokeKey(k.id, value || undefined);
  ElMessage.success(`已吊销 ${k.prefix}`);
  await refresh();
}

async function closeKey() {
  keyOpen.value = false;
  issued.value = undefined;
  await refresh();
}

async function toggle(on: boolean) {
  if (!cur.value) return;
  await props.api.setEnabled(cur.value.id, on);
  ElMessage.success(on ? '已启用' : '已停用');
  await refresh();
}

async function savePerms() {
  if (!cur.value) return;
  await props.api.updatePermissions(cur.value.id, perms(permText.value), rate.value);
  ElMessage.success('已保存');
  await refresh();
}

async function loadAudit() {
  if (cur.value) audit.value = await props.api.audit(cur.value.id, batch.value || undefined);
}

onMounted(load);
defineExpose({ load, open });
</script>

<style scoped>
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mb {
  margin-bottom: 12px;
}
.mt {
  margin-top: 8px;
}
</style>

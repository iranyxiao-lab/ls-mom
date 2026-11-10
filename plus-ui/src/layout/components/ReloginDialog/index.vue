<template>
  <el-dialog
    v-model="visible"
    title="登录已过期"
    width="400px"
    append-to-body
    :close-on-click-modal="false"
    class="ls-relogin"
    data-testid="relogin-dialog"
    @closed="onClosed"
  >
    <p class="ls-relogin__tip">你填写的内容不会丢失。重新登录后会自动继续刚才的操作。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="账号">
        <el-input :model-value="user.name" disabled data-testid="relogin-user" />
      </el-form-item>
      <el-form-item label="密码" :error="error">
        <el-input
          ref="pwdRef"
          v-model="password"
          type="password"
          show-password
          autocomplete="current-password"
          data-testid="relogin-password"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item v-if="captcha" label="验证码">
        <div class="ls-relogin__code">
          <el-input v-model="code" data-testid="relogin-code" @keyup.enter="submit" />
          <img :src="captcha" alt="验证码，点击刷新" @click="loadCaptcha" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-testid="relogin-cancel" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" data-testid="relogin-submit" @click="submit">登录并继续</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useUserStore } from '@/store/modules/user';
import { getCodeImg } from '@/api/login';
import { registerRelogin } from '@/utils/relogin';

const user = useUserStore();
const visible = ref(false);
const password = ref('');
const code = ref('');
const uuid = ref('');
const captcha = ref('');
const error = ref('');
const busy = ref(false);
const pwdRef = ref<{ focus: () => void }>();
let resolve: ((ok: boolean) => void) | null = null;

async function loadCaptcha() {
  try {
    const { data } = await getCodeImg();
    const enabled = data.captchaEnabled === undefined ? true : data.captchaEnabled;
    captcha.value = enabled ? 'data:image/gif;base64,' + data.img : '';
    uuid.value = enabled ? data.uuid : '';
    code.value = '';
  } catch {
    captcha.value = '';
  }
}

function open(): Promise<boolean> {
  password.value = '';
  error.value = '';
  visible.value = true;
  void loadCaptcha();
  void nextTick(() => pwdRef.value?.focus());
  return new Promise((r) => (resolve = r));
}

async function submit() {
  if (!password.value) {
    error.value = '请输入密码';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    await user.login({
      tenantId: user.tenantId || '000000',
      username: user.name,
      password: password.value,
      code: code.value,
      uuid: uuid.value,
      rememberMe: false
    } as any);
    password.value = '';
    resolve?.(true);
    resolve = null;
    visible.value = false;
  } catch {
    error.value = '登录失败，请检查密码';
    if (captcha.value) await loadCaptcha();
  } finally {
    busy.value = false;
  }
}

function onClosed() {
  password.value = '';
  resolve?.(false);
  resolve = null;
}

onMounted(() => registerRelogin(open));
onBeforeUnmount(() => registerRelogin(null));
</script>

<style scoped lang="scss">
.ls-relogin__tip {
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
}
.ls-relogin__code {
  display: flex;
  gap: 8px;
  width: 100%;
  img {
    height: 32px;
    cursor: pointer;
  }
}
</style>

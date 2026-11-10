<template>
  <div class="p-2">
    <el-card shadow="never" data-testid="pref-center">
      <template #header><span>偏好设置</span><span class="hint">保存在服务端，换浏览器或设备登录后保持</span></template>
      <el-form label-width="120px" class="pref-form">
        <el-form-item label="主题">
          <el-radio-group v-model="theme.pref.value" data-testid="pref-theme">
            <el-radio-button value="system">跟随系统</el-radio-button>
            <el-radio-button value="light">浅色</el-radio-button>
            <el-radio-button value="dark">深色</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密度">
          <el-radio-group v-model="density.pref.value" data-testid="pref-density">
            <el-radio-button value="auto">自动</el-radio-button>
            <el-radio-button value="comfortable">舒适</el-radio-button>
            <el-radio-button value="compact">紧凑</el-radio-button>
          </el-radio-group>
          <span class="desc">自动：列表紧凑、表单舒适</span>
        </el-form-item>
        <el-form-item label="导航布局">
          <el-radio-group v-model="settings.navType" data-testid="pref-nav">
            <el-radio-button :value="NavTypeEnum.LEFT">左侧菜单</el-radio-button>
            <el-radio-button :value="NavTypeEnum.TOP">顶部菜单</el-radio-button>
            <el-radio-button :value="NavTypeEnum.MIX">混合</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="多页签">
          <el-switch v-model="settings.tagsView" aria-label="多页签" data-testid="pref-tags" />
        </el-form-item>
        <el-form-item label="快捷键提示">
          <el-switch v-model="hotkeyHints" aria-label="快捷键提示" data-testid="pref-hints" />
          <span class="desc">在命令面板和按钮提示中显示快捷键</span>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">收藏（{{ history.favorites.length }}）</el-divider>
      <LsEmpty v-if="!history.favorites.length" title="还没有收藏" description="在命令面板中按 Ctrl+D，或在对象详情页点「收藏」" />
      <ul v-else class="nav-list" data-testid="pref-favorites">
        <li v-for="f in history.favorites" :key="f.path">
          <router-link :to="f.path">{{ f.title }}</router-link>
          <span class="desc">{{ f.sub }}</span>
          <el-button link type="danger" :aria-label="`取消收藏 ${f.title}`" @click="history.toggleFavorite(f)">取消收藏</el-button>
        </li>
      </ul>

      <el-divider content-position="left">最近访问（{{ history.recent.length }}）</el-divider>
      <el-button :disabled="!history.recent.length" data-testid="pref-clear-recent" @click="history.replace({ recent: [] })">清空最近访问</el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsPrefCenter">
/** 偏好中心：主题、密度、布局、页签、快捷键提示，以及收藏与最近访问管理。修改即生效并同步到服务端。 */
import { LsEmpty } from '@/components/LsState';
import { useDensity, useThemeMode } from '@/design/theme';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { useSettingsStore } from '@/store/modules/settings';
import { useNavHistoryStore } from '@/store/modules/navHistory';
import { hotkeyHints } from '@/utils/prefSync';

const theme = useThemeMode();
const density = useDensity();
const settings = useSettingsStore();
const history = useNavHistoryStore();
</script>

<style scoped lang="scss">
.hint,
.desc {
  margin-left: var(--ls-space-3);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.nav-list {
  list-style: none;
  padding: 0;
  margin: 0;
  li {
    display: flex;
    align-items: center;
    gap: var(--ls-space-2);
    min-height: 36px;
  }
}
</style>

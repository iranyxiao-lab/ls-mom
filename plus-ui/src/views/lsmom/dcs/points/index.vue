<template>
  <div class="p-2">
    <el-card shadow="never">
      <PointList :api="dcsApi" :can-manage="checkPermi(['ls:dcs:manage'])" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LsDcsPoints">
import { dcsApi } from '@/api/lsmom/dcs';
import { checkPermi } from '@/utils/permission';
import PointList from './PointList.vue';

/** DCS：测点语义与设备映射（菜单 /dcs/points）。 */
</script>

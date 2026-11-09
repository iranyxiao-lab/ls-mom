import type { FormField, RefSpec } from '@/types/lsView';

/** 业务引用控件的默认目标 Concept（未显式配置 ref 时）。设备、物料、点位等由 Pack 提供，必须显式配置。 */
export const REF_DEFAULTS: Partial<Record<FormField['widget'], RefSpec>> = {
  userRef: { concept: 'ls.core.Party', labelField: 'name' },
  deptRef: { concept: 'ls.core.OrgUnit', labelField: 'name' },
  teamRef: { concept: 'ls.core.OrgUnit', labelField: 'name', filter: [{ field: 'unitType', op: 'eq', value: 'TEAM' }] },
  workRef: { concept: 'ls.core.Work', labelField: 'title' }
};

export const REF_WIDGETS = new Set(['userRef', 'deptRef', 'teamRef', 'assetRef', 'workRef', 'materialRef', 'pointRef', 'conceptRef', 'remoteSelect']);

export function refOf(f: FormField): RefSpec | undefined {
  return f.ref ?? REF_DEFAULTS[f.widget];
}

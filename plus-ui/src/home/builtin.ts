/** 平台内置首页卡片。 */
import { registerHomeCard } from './registry';
import TeamWorkCard from './cards/TeamWorkCard.vue';
import ReviewsCard from './cards/ReviewsCard.vue';
import IssuesCard from './cards/IssuesCard.vue';
import OnTimeRateCard from './cards/OnTimeRateCard.vue';
import QuickLinksCard from './cards/QuickLinksCard.vue';

let done = false;
export function registerBuiltinHomeCards(): void {
  if (done) return;
  done = true;
  registerHomeCard({ code: 'ls.home.teamWork', title: '本班组今日工作', description: '今天计划、进行中与受阻的工作', component: TeamWorkCard });
  registerHomeCard({ code: 'ls.home.myReviews', title: '待我审核', description: '分给我或我是候选人的流程待办', component: ReviewsCard });
  registerHomeCard({
    code: 'ls.home.openIssues',
    title: '未关闭异常',
    description: '数据域内尚未关闭的异常',
    component: IssuesCard,
    requiresAnyPath: ['/quality/nc', '/asset/defects']
  });
  registerHomeCard({ code: 'ls.home.onTimeRate', title: '本周按期完成率', description: '关键指标（Metric）', component: OnTimeRateCard });
  registerHomeCard({ code: 'ls.home.quickLinks', title: '快捷入口', description: '收藏与常用页面', component: QuickLinksCard });
}

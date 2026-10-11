<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import LinkList from './views/LinkList.vue'
import ChannelFunnel from './views/ChannelFunnel.vue'
import DualMetric from './views/DualMetric.vue'

// 三页用 hash 切换，不引 vue-router：三个静态视图各取各的数，路由库在这里只剩"改 URL"这一件事
const routes = { '#/links': LinkList, '#/funnel': ChannelFunnel, '#/dual': DualMetric }
const titles = { '#/links': '点击统计列表', '#/funnel': '渠道漏斗', '#/dual': '有效 / 疑似刷量双指标' }
const view = ref('#/links')
// 列表页的活动名可以点过来（`#/dual?campaign=…`），所以 hash 里那串查询参数要读出来；
// 直接走导航切换时它自然被清掉——否则上一个活动会一直挂在双指标页上
const campaign = ref('')
const onHash = () => {
  // 用带 '#' 的原串查表：routes 的键就是 '#/links' 这种形态，先把 '#' 切掉会让三页全部回落到第一页
  const [path, qs] = (location.hash || '#/links').split('?')
  view.value = routes[path] ? path : '#/links'
  campaign.value = qs ? new URLSearchParams(qs).get('campaign') || '' : ''
}
onMounted(() => { onHash(); window.addEventListener('hashchange', onHash) })
onUnmounted(() => window.removeEventListener('hashchange', onHash))
const current = computed(() => routes[view.value])

// 租户与窗口是三条接口共同的入参，放在壳层，页面只管渲染。
// 租户号在这里可以被改：看板无鉴权、只出聚合数字，这条边界写在 DESIGN 7.2 与 9.1 风险表，
// 不是页面漏了权限控制——所以也不伪装成"只有主办方能看到自己的"。
const tenant = ref('1001')
const preset = reactive({ value: '24h' })
const gran = ref('')

const params = computed(() => {
  const now = new Date()
  const stamp = (d) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19)
  const to = stamp(now)
  const spans = { '24h': 24 * 3600e3, '7d': 7 * 86400e3, 'today': 0 }
  const from = preset.value === 'today'
    ? stamp(new Date(now.getFullYear(), now.getMonth(), now.getDate()))
    : stamp(new Date(now.getTime() - spans[preset.value]))
  return { from, to, granularity: gran.value, tenant: tenant.value, key: `${preset.value}|${tenant.value}|${gran.value}` }
})
</script>

<template>
  <header class="bar">
    <strong>星演主办方看板</strong>
    <nav>
      <a v-for="(_, path) in routes" :key="path" :href="path"
         :class="{ on: view === path }">{{ titles[path] }}</a>
    </nav>
    <div class="filters">
      <label>租户 <input v-model="tenant" inputmode="numeric" data-testid="tenant" /></label>
      <label>窗口
        <select v-model="preset.value" data-testid="window-preset">
          <option value="today">今天</option>
          <option value="24h">最近 24 小时</option>
          <option value="7d">最近 7 天</option>
        </select>
      </label>
      <label>粒度
        <select v-model="gran" data-testid="granularity">
          <option value="">自动</option>
          <option value="minute">分钟</option>
          <option value="hour">小时</option>
          <option value="day">天</option>
        </select>
      </label>
    </div>
  </header>
  <component :is="current" v-bind="params" :campaign="campaign" />
  <footer class="foot">
    数据只读自 ClickHouse 明细（<code>click_event FINAL</code>）。窗口上限 8 天由实测决定，
    长窗口请用 <code>scripts/recon-attribution.sh</code>；DESIGN 6.4 / 7.2。
  </footer>
</template>

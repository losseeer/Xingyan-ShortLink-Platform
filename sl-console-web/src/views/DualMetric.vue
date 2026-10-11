<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../api.js'
import Chart from '../components/Chart.vue'

const props = defineProps({ from: String, to: String, granularity: String, tenant: String, key: String, campaign: String })
const series = ref([])
const totals = ref({})
const err = ref('')

async function load() {
  err.value = ''
  try {
    // 有 campaign 就走活动趋势（同一套四档口径，只是筛选轴不同），否则用**租户时间序列**那条接口。
    // 这里以前借用了渠道接口的返回——它没有 series，于是页面显示「这个窗口里没有点击」，
    // 而后端一切正常、同窗口确实有 254 击。借错接口不会报错，只会让页面撒谎。
    const w = { from: props.from, to: props.to, granularity: props.granularity }
    const d = props.campaign ? await api.campaign(props.campaign, w)
                              : await api.tenantTimeseries(props.tenant, w)
    series.value = d.series || []
    totals.value = d.totals || {}
  } catch (e) {
    err.value = e.message
    series.value = []
  }
}
watch(() => `${props.key}|${props.campaign || ''}`, load, { immediate: true })

const buckets = computed(() => series.value.map(s => (s.bucket || '').slice(5, 16).replace('T', ' ')))
const option = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['有效点击', '被频控拦下', '疑似刷量（评分层未交付）'] },
  grid: { left: 56, right: 24, top: 44, bottom: 44 },
  xAxis: { type: 'category', data: buckets.value },
  yAxis: { type: 'value' },
  series: [
    { name: '有效点击', type: 'line', smooth: true, data: series.value.map(s => s.valid_pv) },
    { name: '被频控拦下', type: 'line', data: series.value.map(s => s.blocked_pv) },
    { name: '疑似刷量（评分层未交付）', type: 'line', data: series.value.map(s => s.bot_pv) }
  ]
}))
</script>

<template>
  <section>
    <h2>有效 / 疑似刷量 双指标 <small v-if="campaign">（活动 {{ campaign }}）</small></h2>
    <p v-if="err" class="err" data-testid="error">{{ err }}</p>
    <p v-else-if="!series.length" class="empty" data-testid="empty">这个窗口里没有点击。</p>
    <template v-else>
      <div class="cards">
        <div class="card"><span>有效 valid_pv</span><b data-testid="dual-valid">{{ totals.valid_pv ?? '—' }}</b></div>
        <div class="card"><span>被拦 blocked_pv</span><b data-testid="dual-blocked">{{ totals.blocked_pv ?? '—' }}</b></div>
        <div class="card"><span>疑似刷量 bot_pv</span><b data-testid="dual-bot">{{ totals.bot_pv ?? '—' }}</b></div>
      </div>
      <Chart :option="option" testid="dual-chart" />
      <p class="hint">
        <code>bot_pv</code> 现在是<strong>恒 0</strong>，因为行为评分层（DESIGN 5.3 第 3 层）还没交付——
        这是能力边界而不是链路故障。当前唯一有效的风险信号是频控那个 25 分，也就是上面的「被拦」这一条。
        评分层落地后这条式子一个字都不用改，数值自然变准（口径见 6.4）。
      </p>
      <table data-testid="dual-rows">
        <thead><tr><th>时间桶</th><th class="n">点击</th><th class="n">UV</th><th class="n">有效</th>
                  <th class="n">被拦</th><th class="n">疑似刷量</th></tr></thead>
        <tbody>
          <tr v-for="s in series" :key="s.bucket">
            <td>{{ (s.bucket || '').replace('T', ' ') }}</td>
            <td class="n" :data-testid="`bucket-${s.bucket}`">{{ s.pv }}</td>
            <td class="n">{{ s.uv }}</td>
            <td class="n">{{ s.valid_pv }}</td>
            <td class="n">{{ s.blocked_pv }}</td>
            <td class="n">{{ s.bot_pv }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

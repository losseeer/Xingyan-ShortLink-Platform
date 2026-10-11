<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../api.js'
import Chart from '../components/Chart.vue'

const props = defineProps({ from: String, to: String, granularity: String, tenant: String, key: String })
const channels = ref([])
const containers = ref([])
const conversion = ref({})
const err = ref('')

async function load() {
  err.value = ''
  try {
    const d = await api.tenantChannels(props.tenant, { from: props.from, to: props.to, granularity: props.granularity })
    channels.value = d.channels || []
    containers.value = d.containers || []
    conversion.value = d.conversion || {}
  } catch (e) {
    err.value = e.message
    channels.value = []
    containers.value = []
  }
}
watch(() => props.key, load, { immediate: true })

// 三档是"互斥的分类"而不是漏斗的三层，所以画条形图并标清楚，不画成上窄下宽的假漏斗
const option = computed(() => ({
  tooltip: {},
  legend: { data: ['有效', '被频控拦下'] },
  grid: { left: 120, right: 24, top: 36, bottom: 28 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: channels.value.map(c => c.channel_id || '（空）') },
  series: [
    { name: '有效', type: 'bar', stack: 'pv', data: channels.value.map(c => c.valid_pv) },
    { name: '被频控拦下', type: 'bar', stack: 'pv', data: channels.value.map(c => c.blocked_pv) }
  ]
}))
</script>

<template>
  <section>
    <h2>渠道漏斗 <small>（渠道 → 点击 → 有效／被拦）</small></h2>
    <p v-if="err" class="err" data-testid="error">{{ err }}</p>
    <p v-else-if="!channels.length" class="empty" data-testid="empty">这个窗口里没有点击。</p>
    <template v-else>
      <Chart :option="option" testid="funnel-chart" />
      <table data-testid="funnel-rows">
        <thead><tr><th>渠道</th><th class="n">点击 pv</th><th class="n">UV</th><th class="n">有效</th>
                  <th class="n">被拦</th><th class="n">疑似刷量</th><th class="n">转化</th></tr></thead>
        <tbody>
          <tr v-for="c in channels" :key="c.channel_id">
            <td>{{ c.channel_id || '（空）' }}</td>
            <td class="n" :data-testid="`pv-${c.channel_id}`">{{ c.pv }}</td>
            <td class="n">{{ c.uv }}</td>
            <td class="n">{{ c.valid_pv }}</td>
            <td class="n">{{ c.blocked_pv }}</td>
            <td class="n">{{ c.bot_pv }}</td>
            <td class="n na" data-testid="conversion">未接入</td>
          </tr>
        </tbody>
      </table>
      <h3>容器分布</h3>
      <ul class="chips">
        <li v-for="k in containers" :key="k.device_type || '（空）'">
          <code>{{ k.device_type || '（空：UA 分类之前的历史行）' }}</code> {{ k.pv }} 击<span v-if="k.blocked_pv">（被拦 {{ k.blocked_pv }}）</span>
        </li>
      </ul>
      <p class="hint" v-if="conversion.available === false">
        转化那一档<strong>没接</strong>：ClickHouse 里没有订单数据（假订单在 MySQL <code>xsl_mock.mock_order</code>），
        跨源现读会让转化率随两个源的延迟自己跳。<strong>{{ conversion.reason }}</strong>
      </p>
    </template>
  </section>
</template>

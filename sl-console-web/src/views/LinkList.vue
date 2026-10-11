<script setup>
import { ref, watch } from 'vue'
import { api } from '../api.js'

const props = defineProps({ from: String, to: String, granularity: String, tenant: String, key: String })
const links = ref([])
const totals = ref({})
const page = ref({})
const err = ref('')
const busy = ref(false)

async function load() {
  busy.value = true
  err.value = ''
  try {
    const d = await api.tenantLinks(props.tenant, { from: props.from, to: props.to, granularity: props.granularity }, 50, 0)
    links.value = d.links || []
    totals.value = d.totals || {}
    page.value = d.page || {}
  } catch (e) {
    // 后端故障与"这个窗口没人点"必须是两件事：前者要把话说清楚，不能渲染成空表
    err.value = e.message
    links.value = []
  } finally {
    busy.value = false
  }
}
watch(() => props.key, load, { immediate: true })
</script>

<template>
  <section>
    <h2>点击统计列表 <small>（窗口内<strong>有点击</strong>的短码）</small></h2>
    <p class="hint">
      这一页读的是 ClickHouse 的点击明细，不是链接台账：<code>origin_url</code>、状态、过期时间那些管理面字段要 HMAC，
      看板不放密钥（DESIGN 7.2）。所以标题刻意不叫"链接管理"。
    </p>
    <div class="cards">
      <div class="card"><span>点击 pv</span><b data-testid="total-pv">{{ totals.pv ?? '—' }}</b></div>
      <div class="card"><span>独立 IP uv</span><b data-testid="total-uv">{{ totals.uv ?? '—' }}</b></div>
      <div class="card"><span>有效 valid_pv</span><b data-testid="total-valid">{{ totals.valid_pv ?? '—' }}</b></div>
      <div class="card"><span>被拦 blocked_pv</span><b data-testid="total-blocked">{{ totals.blocked_pv ?? '—' }}</b></div>
      <div class="card"><span>疑似刷量 bot_pv</span><b data-testid="total-bot">{{ totals.bot_pv ?? '—' }}</b></div>
    </div>
    <p v-if="busy" class="busy">取数中…</p>
    <p v-else-if="err" class="err" data-testid="error">{{ err }}</p>
    <p v-else-if="!links.length" class="empty" data-testid="empty">这个窗口里没有点击。</p>
    <table v-else data-testid="rows">
      <thead>
        <tr><th>短码</th><th>活动</th><th>渠道</th><th>推广者</th><th class="n">点击</th><th class="n">UV</th>
            <th class="n">有效</th><th class="n">被拦</th><th>最近点击</th></tr>
      </thead>
      <tbody>
        <tr v-for="r in links" :key="r.short_code">
          <td><code>{{ r.short_code }}</code></td>
          <td><a :href="`#/dual?campaign=${encodeURIComponent(r.campaign_id)}`">{{ r.campaign_id || '—' }}</a></td>
          <td>{{ r.channel_id || '—' }}</td>
          <td>{{ r.promoter_id || '—' }}</td>
          <td class="n" :data-testid="`pv-${r.short_code}`">{{ r.pv }}</td>
          <td class="n">{{ r.uv }}</td>
          <td class="n">{{ r.valid_pv }}</td>
          <td class="n">{{ r.blocked_pv }}</td>
          <td>{{ (r.last_click_time || '').replace('T', ' ') }}</td>
        </tr>
      </tbody>
    </table>
    <p class="meta" v-if="page.returned">返回 {{ page.returned }} 条<span v-if="page.has_more">（还有更多，已截断）</span></p>
  </section>
</template>

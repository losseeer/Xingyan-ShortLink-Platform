<script setup>
// ECharts 的一个薄壳：只做"有数据就画、容器变了就重画"，不做配置项透传的抽象层。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ option: { type: Object, required: true }, testid: { type: String, default: 'chart' } })
const el = ref(null)
let chart = null
let ro = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  // notMerge:true：换窗口时 series 组数会变（比如渠道从 3 个变 1 个），
  // 默认合并会把上一轮多出来的那条线留在图上——那种"图上有但数据里没有"的序列最难被发现
  chart.setOption(props.option, true)
}

onMounted(() => {
  render()
  ro = new ResizeObserver(() => chart && chart.resize())
  ro.observe(el.value)
})
watch(() => props.option, render)
onBeforeUnmount(() => { ro && ro.disconnect(); chart && chart.dispose() })
</script>

<template>
  <div ref="el" class="chart" :data-testid="testid"></div>
</template>

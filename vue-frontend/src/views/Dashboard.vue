<template><div><div class="page-heading"><div><div class="eyebrow-label">MISSION OVERVIEW / {{today}}</div><h1>数据驾驶舱</h1><p>实时掌握航拍任务、影像处理与 AI 解译产能</p></div><div class="heading-actions"><el-button plain @click="$router.push('/command')"><el-icon><Operation/></el-icon> 指挥中心</el-button><el-button type="primary" @click="$router.push('/tasks')"><el-icon><Plus/></el-icon> 新建解译任务</el-button></div></div><div class="grid stats"><div v-for="m in metrics" :key="m.name" class="panel metric-card"><div class="metric-icon" :class="m.class">{{m.icon}}</div><div class="metric-content"><span class="muted">{{m.name}}</span><div class="metric">{{m.value}}</div><span class="metric-note"><span :style="{color:m.color}">{{m.trend}}</span> {{m.note}}</span></div></div></div><div class="grid two" style="margin-top:17px"><div class="panel chart-panel"><div class="panel-title"><div><b>任务处理趋势</b><span class="muted">最近 7 日 · 新建 / 成功</span></div><el-tag effect="dark" type="success">LIVE</el-tag></div><div ref="trend" class="chart"/></div><div class="panel chart-panel"><div class="panel-title"><div><b>算法调用分布</b><span class="muted">累计解译次数</span></div><el-button link type="primary" @click="$router.push('/algorithms')">模型中心 →</el-button></div><div ref="pie" class="chart"/></div></div><div class="grid three-panels" style="margin-top:17px"><div class="panel"><div class="panel-title"><b>实时任务队列</b><el-button link type="primary" @click="$router.push('/tasks')">查看全部 →</el-button></div><div v-if="!jobs.length" class="muted" style="padding:12px 0">暂无任务，先到「航拍任务」创建</div><div v-for="job in jobs" :key="job.name" class="job-row"><div class="job-icon">{{job.icon}}</div><div class="job-main"><b>{{job.name}}</b><span>{{job.region}} · {{job.algorithm}}</span></div><el-tag :type="job.type" effect="dark">{{job.status}}</el-tag></div></div><div class="panel"><div class="panel-title"><b>引擎健康度</b><span class="muted">{{onlineEngines}} / {{engines.length}} 就绪</span></div><div v-for="engine in engines" :key="engine.name" class="engine-row"><div><span class="engine-dot"></span><b>{{engine.name}}</b></div><span>{{engine.latency}}</span><div class="progress-line"><i :style="{width:engine.health+'%'}"></i></div></div></div><div class="panel quick-panel"><div class="panel-title"><b>快捷操作</b><span class="muted">QUICK ACTIONS</span></div><button v-for="action in actions" :key="action.title" class="quick-action" @click="$router.push(action.to)"><span class="quick-action-icon">{{action.icon}}</span><span><b>{{action.title}}</b><small>{{action.note}}</small></span><span class="arrow">→</span></button></div></div></div></template>
<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { Plus, Operation } from '@element-plus/icons-vue'
import { dashboardApi, taskApi, algorithmApi } from '../api/http'

const ALGORITHM_LABEL = { camouflaged_object_detection: '伪装目标检测', change_detection: '变化检测', semantic_segmentation: '语义分割' }
const trend = ref(), pie = ref()
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
const onlineEngines = ref(0)
const metrics = ref([
  { name: '任务总数', value: '—', trend: '同步中', note: '累计创建', color: '#42bfff', icon: '◈', class: 'blue' },
  { name: '已入库影像', value: '—', trend: '磁盘实际文件', note: '影像数据', color: '#ffc65b', icon: '⌁', class: 'yellow' },
  { name: '已完成解译', value: '—', trend: '同步中', note: '成功归档', color: '#42e3b0', icon: '✓', class: 'green' },
  { name: '可导出成果', value: '—', trend: '成功解译记录', note: '成果导出', color: '#9b8cff', icon: '▧', class: 'purple' }
])
const jobs = ref([])
const engines = ref([])
const actions = [{ icon: '＋', title: '新建解译任务', note: '创建航拍作业', to: '/tasks' }, { icon: '⇧', title: '上传遥感影像', note: '导入本地数据', to: '/images' }, { icon: '◎', title: '查看实时监测', note: '天气与作业风险', to: '/monitoring' }]
let charts = []

onMounted(async () => {
  const common = { textStyle: { color: '#8eafc4' }, axisLine: { lineStyle: { color: '#23445e' } }, splitLine: { lineStyle: { color: '#17364e' } } }
  charts = [echarts.init(trend.value), echarts.init(pie.value)]
  charts[0].setOption({
    grid: { left: 35, right: 18, top: 30, bottom: 34 }, tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: '#91adc2' } },
    xAxis: { ...common, type: 'category', data: [] }, yAxis: { ...common, type: 'value', minInterval: 1 },
    series: [
      { name: '新建任务', type: 'line', smooth: true, symbol: 'circle', symbolSize: 7, data: [], lineStyle: { color: '#42bfff', width: 3 }, itemStyle: { color: '#42bfff' } },
      { name: '解译成功', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, data: [], lineStyle: { color: '#3ee0ad', width: 3 }, itemStyle: { color: '#3ee0ad' } }
    ]
  })
  charts[1].setOption({
    tooltip: { trigger: 'item' }, legend: { bottom: 0, textStyle: { color: '#91adc2' } },
    series: [{ type: 'pie', radius: ['46%', '72%'], center: ['50%', '44%'], data: [], label: { show: false }, color: ['#42bfff', '#3ee0ad', '#8f83ff'] }]
  })

  try {
    const summary = await dashboardApi.summary()
    metrics.value[0].value = summary.totalTasks
    metrics.value[1].value = summary.images
    metrics.value[2].value = summary.completedTasks
    metrics.value[2].trend = summary.successRate + '% 成功率'
    metrics.value[3].value = summary.successInterpretations
  } catch { }
  try {
    const data = await dashboardApi.trend(7)
    charts[0].setOption({ xAxis: { data: data.labels }, series: [{ data: data.createdTasks }, { data: data.successInterpretations }] })
  } catch { }
  try {
    const distribution = await dashboardApi.algorithms()
    charts[1].setOption({ series: [{ data: distribution.map(item => ({ value: item.interpretations, name: ALGORITHM_LABEL[item.algorithm] || item.algorithm })) }] })
  } catch { }
  try {
    const status = await algorithmApi.list()
    engines.value = (status.algorithms || []).map(item => ({ name: ALGORITHM_LABEL[item.algorithm] || item.algorithm, latency: (item.weights_ready ? '权重就绪' : '未加载权重') + ' · ' + (item.model || ''), health: item.runtime_ready ? 100 : 0 }))
    onlineEngines.value = engines.value.filter(e => e.health > 0).length
  } catch { }
  try {
    const list = await taskApi.list()
    jobs.value = list.slice(-3).reverse().map(t => ({ icon: '◌', name: t.name, region: t.region || '未设置区域', algorithm: ALGORITHM_LABEL[t.algorithm] || t.algorithm || '未指定', status: t.status === 'COMPLETED' ? '已完成' : t.status === 'PROCESSING' ? '处理中' : t.status === 'FAILED' ? '已失败' : '排队中', type: t.status === 'COMPLETED' ? 'success' : t.status === 'PROCESSING' ? 'warning' : t.status === 'FAILED' ? 'danger' : 'info' }))
  } catch { }
})
onBeforeUnmount(() => charts.forEach(c => c.dispose()))
</script>

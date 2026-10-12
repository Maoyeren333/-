<template><div><div class="page-heading"><div><div class="eyebrow-label">OPERATIONS CENTER / CONTROL ROOM</div><h1>智能指挥中心</h1><p>从任务规划到成果交付的一站式操作台</p></div><el-tag :type="serviceCount && onlineCount===serviceCount ? 'success' : serviceCount ? 'warning' : 'info'" effect="dark">{{serviceCount ? onlineCount + ' / ' + serviceCount + ' ONLINE' : 'CHECKING…'}}</el-tag></div><div class="command-hero panel"><div><span class="eyebrow-label">TODAY'S MISSION</span><h2>让每一架无人机的影像都产生决策价值</h2><p>快速创建任务、准备影像、选择算法并跟踪成果。</p></div><div class="hero-orbit"><span class="orbit-core">◈</span><i></i><i></i></div></div><div class="grid stats" style="margin-top:17px"><div v-for="m in metrics" :key="m.name" class="panel metric-card"><div class="metric-icon" :class="m.class">{{m.icon}}</div><div class="metric-content"><span class="muted">{{m.name}}</span><div class="metric">{{m.value}}</div><span class="metric-note">{{m.note}}</span></div></div></div><div class="grid command-grid"><div class="panel"><div class="panel-title"><b>工作流快捷入口</b><span class="muted">START A WORKFLOW</span></div><button v-for="item in workflows" :key="item.title" class="workflow-card" @click="$router.push(item.to)"><span class="workflow-icon">{{item.icon}}</span><span><b>{{item.title}}</b><small>{{item.description}}</small></span><span class="arrow">→</span></button></div><div class="panel"><div class="panel-title"><b>平台状态</b><span class="muted">{{generatedAt}} · {{onlineCount}} / {{serviceCount}} ONLINE</span><el-button link type="primary" :loading="loading" @click="load">刷新</el-button></div><div v-for="service in services" :key="service.name" class="service-row"><span class="service-dot"></span><div><b>{{service.name}}</b><small>{{service.detail}}</small></div><strong>{{service.status}} · {{service.latencyMs}} ms</strong></div></div></div><div class="grid command-grid" style="margin-top:17px"><div class="panel"><div class="panel-title"><b>今日工作建议</b><span class="muted">AI OPERATIONS GUIDE</span></div><div v-for="tip in tips" :key="tip.title" class="tip-row"><span class="tip-number">{{tip.number}}</span><div><b>{{tip.title}}</b><p>{{tip.text}}</p></div></div></div><div class="panel"><div class="panel-title"><b>最近活动</b><el-button link type="primary" @click="$router.push('/tasks')">任务中心 →</el-button></div><div v-if="!events.length" class="muted" style="padding:12px 0">暂无活动记录</div><div v-for="(event, index) in events" :key="index" class="activity-row"><span class="activity-time">{{event.time}}</span><span class="activity-line"></span><div><b>{{event.title}}</b><small>{{event.detail}}</small></div></div></div></div></div></template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { commandApi } from '../api/http'

const workflows = [{ icon: '＋', title: '创建航拍任务', description: '设置区域、算法和作业计划', to: '/tasks' }, { icon: '⇧', title: '导入遥感影像', description: '上传 TIFF / GeoTIFF / JPG / PNG', to: '/images' }, { icon: '◎', title: '查看实时态势', description: '天气、风险和环境质量', to: '/monitoring' }, { icon: '▦', title: '选择解译算法', description: '配置三种 AI 推理引擎', to: '/algorithms' }]
const tips = ref([])

const services = ref([])
const events = ref([])
const generatedAt = ref('—')
const loading = ref(false)
const metrics = ref([
  { name: '任务总数', icon: '◈', class: 'blue', value: '—', note: '累计创建' },
  { name: '已入库影像', icon: '⌁', class: 'yellow', value: '—', note: '磁盘文件' },
  { name: '解译记录', icon: '✓', class: 'green', value: '—', note: '全部提交' },
  { name: '可导出成果', icon: '▧', class: 'purple', value: '—', note: '成功解译' }
])
const serviceCount = computed(() => services.value.length)
const onlineCount = computed(() => services.value.filter(s => s.status === 'ONLINE' || s.status === 'LIVE').length)

async function load() {
  loading.value = true
  try {
    const data = await commandApi.overview()
    services.value = data.services || []
    events.value = data.events || []
    generatedAt.value = data.generatedAt || '—'
    tips.value = data.tips && data.tips.length ? data.tips : [{ number: '01', title: '等待平台数据', text: '正在读取真实状态，请稍后刷新。' }]
    const m = data.metrics || {}
    metrics.value[0].value = m.tasks ?? '—'
    metrics.value[1].value = m.images ?? '—'
    metrics.value[2].value = m.interpretations ?? '—'
    metrics.value[3].value = m.successInterpretations ?? '—'
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '指挥中心数据加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

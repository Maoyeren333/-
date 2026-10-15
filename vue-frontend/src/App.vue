<template>
  <router-view v-if="$route.meta.public" />
  <el-container v-else class="shell">
    <el-aside class="sidebar" width="248px">
      <div class="brand"><span class="brand-mark">◈</span><span>UAV<span class="brand-accent">·VISION</span></span><small>REMOTE INTELLIGENCE</small></div>
      <div class="menu-caption">OPERATIONS CENTER</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon><span>数据驾驶舱</span><b class="menu-badge">LIVE</b></el-menu-item><el-menu-item index="/command"><el-icon><Operation /></el-icon><span>智能指挥中心</span></el-menu-item>
        <el-menu-item index="/monitoring"><el-icon><Monitor /></el-icon><span>实时监测</span><b class="menu-badge">NOW</b></el-menu-item><el-menu-item index="/tasks"><el-icon><MapLocation /></el-icon><span>航拍任务</span></el-menu-item>
        <el-menu-item index="/images"><el-icon><UploadFilled /></el-icon><span>影像数据</span></el-menu-item>
        <el-menu-item index="/algorithms"><el-icon><Cpu /></el-icon><span>智能解译</span></el-menu-item>
        <el-menu-item index="/results"><el-icon><PictureFilled /></el-icon><span>结果中心</span></el-menu-item>
        <el-menu-item index="/exports"><el-icon><Download /></el-icon><span>成果导出</span></el-menu-item>
      </el-menu>
      <div class="sidebar-footer"><div class="service-dot" :class="engine.online ? '' : 'offline'"></div><div><strong>AI SERVICE</strong><span>{{ engine.label }}</span></div><el-icon><Setting /></el-icon></div>
    </el-aside>
    <el-container>
      <el-header class="topbar"><div><span class="eyebrow">UAV REMOTE SENSING / </span><span class="current-page">{{ pageTitle }}</span></div><div class="top-actions"><button class="global-search" @click="openSearch"><el-icon><Search /></el-icon><span>全局搜索</span><kbd>⌘ K</kbd></button><span class="system-time">{{ time }}</span><el-badge :value="noticeCount" :hidden="!noticeCount" class="notice-badge"><el-button circle plain @click="openNotices"><el-icon><Bell /></el-icon></el-button></el-badge><el-dropdown @command="userAction"><div class="user-chip"><div class="avatar">{{ initials }}</div><span>{{ userName }}</span><el-icon><ArrowDown /></el-icon></div><template #dropdown><el-dropdown-menu><el-dropdown-item command="profile">个人中心</el-dropdown-item><el-dropdown-item divided command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></el-header>
      <el-main class="workspace"><router-view /></el-main>
    </el-container>
  </el-container>
  <el-dialog v-model="searchOpen" title="全局搜索" width="560px"><el-input v-model="searchText" autofocus placeholder="搜索任务、影像、算法或平台功能" :prefix-icon="Search"/><div class="search-results"><button v-for="item in searchResults" :key="item.key" class="search-result" @click="go(item.to)"><span>{{item.icon}}</span><div><b>{{item.title}}</b><small>{{item.detail}}</small></div><em>↵</em></button><div v-if="!searchResults.length" class="empty-state">没有匹配的功能或任务</div></div></el-dialog>
  <el-drawer v-model="noticeOpen" title="通知中心" size="380px"><div v-if="!notices.length" class="empty-state">暂无通知</div><div v-for="(notice,index) in notices" :key="index" class="notice-item"><span class="notice-icon" :class="notice.level.toLowerCase()">{{notice.icon}}</span><div><b>{{notice.title}}</b><p>{{notice.detail}}</p><small>{{notice.time}}</small></div></div></el-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { DataAnalysis, MapLocation, UploadFilled, Cpu, PictureFilled, Download, Setting, Bell, ArrowDown, Monitor, Operation, Search } from '@element-plus/icons-vue'
import { authApi, notificationApi, algorithmApi, taskApi } from './api/http'
const router = useRouter()
const route = useRoute()
const time = ref(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
const userName = ref('访客')
const initials = ref('GU')
const searchOpen = ref(false)
const noticeOpen = ref(false)
const searchText = ref('')
const notices = ref([])
const noticeCount = ref(0)
const searchTasks = ref([])
const engine = ref({ online: 0, total: 0, label: '检测中…' })
const timer = setInterval(() => { time.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }, 1000)
onBeforeUnmount(() => clearInterval(timer))

onMounted(async () => {
  loadUser()
  loadNotices()
  loadEngine()
  try { searchTasks.value = await taskApi.list() } catch { }
})

// App 在公开页（登录页）就已挂载，故进入受保护路由时需要重新拉取当前用户
watch(() => route.path, path => { if (!route.meta.public) loadUser() })

async function loadUser() {
  try {
    const user = (await authApi.me()).user
    userName.value = user.displayName || user.username
    initials.value = userName.value.slice(0, 2).toUpperCase()
  } catch { }
}

async function loadNotices() {
  try {
    const data = await notificationApi.list()
    notices.value = data.items || []
    noticeCount.value = data.count || 0
  } catch {
    notices.value = [{ level: 'WARNING', icon: '!', title: '通知加载失败', detail: '请确认后端服务是否正常', time: '实时' }]
    noticeCount.value = 1
  }
}

async function loadEngine() {
  try {
    const data = await algorithmApi.list()
    const list = data.algorithms || []
    const online = list.filter(item => item.runtime_ready).length
    engine.value = { online, total: list.length, label: online + ' / ' + list.length + ' engines ready' }
  } catch {
    engine.value = { online: 0, total: 0, label: '推理服务离线' }
  }
}

function openNotices() { noticeOpen.value = true; loadNotices() }
function openSearch() { searchOpen.value = true; taskApi.list().then(list => { searchTasks.value = list }).catch(() => { }) }

const pageTitle = computed(() => ({ '/dashboard': 'DATA COCKPIT', '/command': 'CONTROL ROOM', '/monitoring': 'LIVE MONITORING', '/tasks': 'MISSION CONTROL', '/images': 'IMAGERY HUB', '/algorithms': 'AI ENGINES', '/results': 'RESULTS CENTER', '/exports': 'DELIVERY CENTER', '/profile': 'USER PROFILE' }[route.path] || 'PLATFORM'))
const searchItems = [{ key: 'p1', icon: '⌂', title: '数据驾驶舱', detail: '任务统计与 AI 产能趋势', to: '/dashboard' }, { key: 'p2', icon: '◈', title: '智能指挥中心', detail: '平台工作流与系统状态', to: '/command' }, { key: 'p3', icon: '◎', title: '实时监测', detail: '天气、风险与环境质量', to: '/monitoring' }, { key: 'p4', icon: '▣', title: '航拍任务', detail: '创建和管理解译任务', to: '/tasks' }, { key: 'p5', icon: '⇧', title: '影像数据', detail: '上传原始遥感影像', to: '/images' }, { key: 'p6', icon: '▦', title: '智能解译', detail: 'C2FNet 伪装目标检测', to: '/algorithms' }, { key: 'p7', icon: '🖼', title: '结果中心', detail: '查看解译掩膜与预览', to: '/results' }, { key: 'p8', icon: '⬇', title: '成果导出', detail: '打包下载检测成果', to: '/exports' }]
const searchResults = computed(() => {
  const text = searchText.value.trim().toLowerCase()
  const pages = searchItems.filter(item => !text || `${item.title}${item.detail}`.toLowerCase().includes(text))
  const tasks = !text ? [] : searchTasks.value
    .filter(item => `${item.name}${item.region || ''}`.toLowerCase().includes(text))
    .slice(0, 5)
    .map(item => ({ key: 't' + item.id, icon: '▣', title: item.name, detail: `任务 #${item.id} · ${item.region || '未设置区域'} · ${item.status}`, to: '/tasks' }))
  return [...tasks, ...pages]
})
function go(path) { searchOpen.value = false; searchText.value = ''; router.push(path) }
async function userAction(command) { if (command === 'profile') router.push('/profile'); if (command === 'logout') { await authApi.logout(); router.push('/login') } }
</script>

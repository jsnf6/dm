<template>
  <div class="ai-chat-wrapper">
    <transition name="ball-fade">
      <div v-show="!chatVisible" class="ai-chat-ball" @click="openChat">
        <el-icon :size="26"><ChatDotRound /></el-icon>
      </div>
    </transition>

    <transition name="panel-slide">
      <div v-show="chatVisible" class="ai-chat-panel" :style="panelStyle">
        <!-- resize handles -->
        <div class="resize-handle resize-n" @mousedown.prevent="startResize($event, 'n')"></div>
        <div class="resize-handle resize-w" @mousedown.prevent="startResize($event, 'w')"></div>
        <div class="resize-handle resize-nw" @mousedown.prevent="startResize($event, 'nw')"></div>

        <div class="ai-chat-sidebar" v-show="sidebarVisible">
          <div class="sidebar-header">
            <span class="sidebar-title">History</span>
            <el-icon :size="18" class="sidebar-close" @click="sidebarVisible = false"><Close /></el-icon>
          </div>
          <div class="sidebar-list">
            <template v-for="group in conversationGroups" :key="group.label">
              <div class="sidebar-divider">{{ group.label }}</div>
              <div
                v-for="conv in group.items"
                :key="conv.id"
                :class="['sidebar-item', { active: conv.id === sessionId }]"
                @click="switchConversation(conv.id)"
              >
                <div class="sidebar-item-info">
                  <div class="sidebar-item-title">{{ conv.title }}</div>
                  <div class="sidebar-item-time">{{ conv.time }}</div>
                </div>
                <el-icon :size="14" class="sidebar-item-delete" @click.stop="deleteConversationHandler(conv.id)"><Delete /></el-icon>
              </div>
            </template>
            <div v-if="convList.length === 0 && !convLoading" class="sidebar-empty">No history</div>
            <div v-if="convLoading" class="sidebar-empty">Loading...</div>
          </div>
        </div>

        <div class="ai-chat-header">
          <div class="header-left">
            <el-icon :size="18" class="header-icon" @click="toggleSidebar" title="History"><Expand /></el-icon>
            <el-icon :size="20"><Service /></el-icon>
            <span class="header-title">AI Customer Service</span>
            <span class="header-status"><span class="status-dot"></span>Online</span>
          </div>
          <div class="header-right">
            <el-icon :size="18" class="header-icon" @click="newConversation" title="New Chat"><Plus /></el-icon>
            <el-icon :size="18" class="header-icon" @click="clearChat" title="Clear"><Delete /></el-icon>
            <el-icon :size="20" class="header-icon header-close" @click="closeChat"><Close /></el-icon>
          </div>
        </div>

        <div class="ai-chat-body" ref="chatBodyRef">
          <div v-if="messages.length === 0" class="welcome-area">
            <div class="welcome-icon"><el-icon :size="48"><Service /></el-icon></div>
            <div class="welcome-title">Hello, I am AI Customer Service</div>
            <div class="welcome-desc">What can I help you with?</div>
            <div class="quick-questions">
              <div v-for="(q, idx) in quickQuestions" :key="idx" class="quick-item" @click="sendQuickQuestion(q)">{{ q }}</div>
            </div>
          </div>

          <div v-for="(msg, idx) in messages" :key="idx" :class="['message-item', msg.role === 'user' ? 'message-user' : 'message-ai']">
            <div class="message-avatar">
              <el-avatar :size="32" v-if="msg.role === 'ai'"><el-icon :size="18"><Service /></el-icon></el-avatar>
              <el-avatar :size="32" v-else><el-icon :size="18"><UserFilled /></el-icon></el-avatar>
            </div>
            <div class="message-content">
              <div v-if="msg.content === '__LOADING__'" class="typing-indicator"><span></span><span></span><span></span></div>
              <div v-else class="message-text" v-html="formatMessage(msg.content)"></div>
              <div class="message-time">{{ msg.time }}</div>
              <div v-if="msg.role === 'ai' && msg.content !== '__LOADING__' && !isLoading" class="msg-actions">
                <span :class="[`msg-feedback`, { active: msg.satisfaction === 1 }]" @click="submitFeedback(msg, 1)" title="Useful"><el-icon :size="14"><CaretTop /></el-icon></span>
                <span :class="[`msg-feedback`, { active: msg.satisfaction === 0 }]" @click="submitFeedback(msg, 0)" title="Not useful"><el-icon :size="14"><CaretBottom /></el-icon></span>
                <span class="msg-copy" @click="copyMessage(msg.content)"><el-icon :size="14"><DocumentCopy /></el-icon></span>
                <span class="msg-refresh" @click="regenerate(idx)"><el-icon :size="14"><Refresh /></el-icon></span>
              </div>
            </div>
          </div>
        </div>

        <div class="ai-chat-footer">
          <el-input v-model="inputText" type="textarea" :rows="1" :autosize="{ minRows: 1, maxRows: 3 }" placeholder="Type your question..." resize="none" @keydown.enter.exact.prevent="sendMessage" />
          <el-button type="primary" :disabled="!inputText.trim() || isLoading" @click="sendMessage" class="send-btn" v-if="!isLoading">
            <el-icon :size="18"><Promotion /></el-icon>
          </el-button>
          <el-button type="danger" class="send-btn stop-btn" v-if="isLoading" @click="stopGeneration">
            <el-icon :size="18"><Close /></el-icon>
          </el-button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, watch, onMounted, onUnmounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Service, Close, Delete, UserFilled, Promotion, Plus, Refresh, Expand, DocumentCopy, CaretTop, CaretBottom } from '@element-plus/icons-vue'
import { sendAiMessageStream, getConversations, getConversationMessages, deleteConversation, submitSatisfaction } from '@/api/aiChat'
import useUserStore from '@/store/modules/user'
import { marked } from 'marked'

const userStore = useUserStore()

const MIN_W = 340, MAX_W = 700, MIN_H = 420, MAX_H = 800
const panelWidth = ref(380)
const panelHeight = ref(520)

const panelStyle = computed(() => ({
  width: panelWidth.value + 'px',
  height: panelHeight.value + 'px'
}))

// ---- resize ----
const resizing = ref(null)
const resizeStart = { x: 0, y: 0, w: 0, h: 0 }

function startResize(e, dir) {
  resizing.value = dir
  resizeStart.x = e.clientX
  resizeStart.y = e.clientY
  resizeStart.w = panelWidth.value
  resizeStart.h = panelHeight.value
  document.body.style.userSelect = 'none'
  document.body.style.cursor = dir === 'nw' ? 'nw-resize' : dir + '-resize'
  window.addEventListener('mousemove', onResize)
  window.addEventListener('mouseup', stopResize)
}

function onResize(e) {
  if (!resizing.value) return
  const dx = resizeStart.x - e.clientX
  const dy = resizeStart.y - e.clientY
  if (resizing.value.includes('w')) {
    panelWidth.value = Math.min(MAX_W, Math.max(MIN_W, resizeStart.w + dx))
  }
  if (resizing.value.includes('n')) {
    panelHeight.value = Math.min(MAX_H, Math.max(MIN_H, resizeStart.h + dy))
  }
}

function stopResize() {
  resizing.value = null
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
  window.removeEventListener('mousemove', onResize)
  window.removeEventListener('mouseup', stopResize)
}

onUnmounted(() => {
  window.removeEventListener('mousemove', onResize)
  window.removeEventListener('mouseup', stopResize)
})

// ---- chat logic ----
const chatVisible = ref(false)
const inputText = ref('')
const isLoading = ref(false)
const chatBodyRef = ref(null)
const messages = reactive([])
const sessionId = ref('')
const abortController = ref(null)
const sidebarVisible = ref(false)
const convList = ref([])
const convLoading = ref(false)

function getTimeCategory(timestamp) {
  const now = new Date()
  const date = new Date(timestamp)
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 86400000
  const weekStart = todayStart - 7 * 86400000
  const monthStart = todayStart - 30 * 86400000
  const t = date.getTime()
  if (t >= todayStart) return 'Today'
  if (t >= yesterdayStart) return 'Yesterday'
  if (t >= weekStart) return '7 Days'
  if (t >= monthStart) return '30 Days'
  return 'Earlier'
}

function formatConvTime(timestamp) {
  const d = new Date(timestamp)
  const month = (d.getMonth() + 1).toString().padStart(2, '0')
  const day = d.getDate().toString().padStart(2, '0')
  const hour = d.getHours().toString().padStart(2, '0')
  const min = d.getMinutes().toString().padStart(2, '0')
  return month + '/' + day + ' ' + hour + ':' + min
}

const conversationGroups = computed(() => {
  const groups = {}
  for (const conv of convList.value) {
    const cat = getTimeCategory(conv.updatedAt)
    if (!groups[cat]) groups[cat] = []
    groups[cat].push({ ...conv, time: formatConvTime(conv.updatedAt) })
  }
  const order = ['Today', 'Yesterday', '7 Days', '30 Days', 'Earlier']
  return order.filter(l => groups[l]).map(l => ({ label: l, items: groups[l] }))
})

const quickQuestions = [
  'How to buy tickets?',
  'How to refund?',
  'How to check order status?',
  'What payment methods are supported?'
]

onMounted(() => {
  sessionId.value = 'ai_chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11)
  fetchConversations()
})

async function fetchConversations() {
  convLoading.value = true
  try {
    const res = await getConversations()
    if (res.code === 0 && Array.isArray(res.data)) {
      convList.value = res.data.sort((a, b) => b.updatedAt - a.updatedAt)
    }
  } catch (e) { /* ignore */ }
  finally { convLoading.value = false }
}

async function switchConversation(id) {
  if (id === sessionId.value) { sidebarVisible.value = false; return }
  isLoading.value = false
  if (abortController.value) { abortController.value.abort(); abortController.value = null }
  messages.splice(0)
  sessionId.value = id
  sidebarVisible.value = false
  try {
    const res = await getConversationMessages(id)
    if (res.code === 0 && Array.isArray(res.data)) {
      res.data.forEach(m => messages.push({ role: m.role, content: m.content, time: m.time }))
    }
  } catch (e) { /* ignore */ }
  nextTick(() => scrollToBottom())
}

async function deleteConversationHandler(id) {
  try { await deleteConversation(id) } catch (e) { /* ignore */ }
  convList.value = convList.value.filter(c => c.id !== id)
  if (id === sessionId.value) {
    messages.splice(0)
    if (convList.value.length > 0) { switchConversation(convList.value[0].id) }
    else { sessionId.value = 'ai_chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11) }
  }
}

function toggleSidebar() {
  sidebarVisible.value = !sidebarVisible.value
  if (sidebarVisible.value) fetchConversations()
}

function openChat() { chatVisible.value = true; nextTick(() => scrollToBottom()) }
function closeChat() { chatVisible.value = false; sidebarVisible.value = false }

function clearChat() { messages.splice(0) }

function newConversation() {
  messages.splice(0)
  sessionId.value = 'ai_chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11)
  const now = new Date()
  const timeStr = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0')
  messages.push({ role: 'ai', content: 'New conversation started. How can I help you?', time: timeStr, streaming: false })
}

function sendQuickQuestion(question) { inputText.value = question; sendMessage() }

function stopGeneration() {
  if (abortController.value) { abortController.value.abort(); abortController.value = null; isLoading.value = false }
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isLoading.value) return
  const now = new Date()
  const timeStr = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0')
  messages.push({ role: 'user', content: text, time: timeStr })
  inputText.value = ''
  isLoading.value = true
  await nextTick()
  scrollToBottom()
  const aiMsg = reactive({ role: 'ai', content: '__LOADING__', time: '' })
  messages.push(aiMsg)
  await nextTick()
  try {
    abortController.value = new AbortController()
    let firstChunk = true
    for await (const chunk of sendAiMessageStream(text, sessionId.value, abortController.value.signal)) {
      if (firstChunk) { aiMsg.content = chunk; firstChunk = false }
      else { aiMsg.content += chunk }
      await nextTick()
      scrollToBottom()
    }
    const doneTime = new Date()
    aiMsg.time = doneTime.getHours().toString().padStart(2, '0') + ':' + doneTime.getMinutes().toString().padStart(2, '0')
    syncMessageIds()
  } catch (error) {
    if (error && error.name === 'AbortError') {
      const idx = messages.indexOf(aiMsg)
      if (idx >= 0) messages.splice(idx, 1)
      return
    }
    if (aiMsg.content === '__LOADING__' || aiMsg.content === '') {
      aiMsg.content = 'Sorry, service unavailable. Please try again.'
    }
    const errTime = new Date()
    aiMsg.time = errTime.getHours().toString().padStart(2, '0') + ':' + errTime.getMinutes().toString().padStart(2, '0')
  } finally {
    isLoading.value = false
    abortController.value = null
    await nextTick()
    scrollToBottom()
  }
}

function regenerate(aiIdx) {
  if (isLoading.value) return
  let userMsg = null
  for (let i = aiIdx - 1; i >= 0; i--) {
    if (messages[i].role === 'user') { userMsg = messages[i]; break }
  }
  if (!userMsg) return
  const aiMsg = messages[aiIdx]
  aiMsg.content = '__LOADING__'
  aiMsg.time = ''
  isLoading.value = true
  const prompt = userMsg.content
  nextTick(async () => {
    try {
      abortController.value = new AbortController()
      let firstChunk = true
      for await (const chunk of sendAiMessageStream(prompt, sessionId.value, abortController.value.signal)) {
        if (firstChunk) { aiMsg.content = chunk; firstChunk = false }
        else { aiMsg.content += chunk }
        await nextTick()
        scrollToBottom()
      }
      const doneTime = new Date()
      aiMsg.time = doneTime.getHours().toString().padStart(2, '0') + ':' + doneTime.getMinutes().toString().padStart(2, '0')
      syncMessageIds()
    } catch (error) {
      if (error && error.name === 'AbortError') return
      if (aiMsg.content === '__LOADING__' || aiMsg.content === '') {
        aiMsg.content = 'Sorry, service unavailable. Please try again.'
      }
    } finally {
      isLoading.value = false
      abortController.value = null
      await nextTick()
      scrollToBottom()
    }
  })
}

function submitFeedback(msg, val) {
  if (!msg.id) return
  submitSatisfaction(msg.id, val).then(() => {
    msg.satisfaction = val
  }).catch(() => {})
}

function copyMessage(content) {
  navigator.clipboard.writeText(content).then(() => {
    ElMessage.success('Copied!')
  }).catch(() => {})
}
function formatMessage(content) {
  if (!content) return ''
  marked.setOptions({ breaks: true, gfm: true })
  return marked.parse(content)
}

async function syncMessageIds() {
  fetchConversations()
  try {
    const res = await getConversationMessages(sessionId.value)
    if (res.code === 0 && Array.isArray(res.data)) {
      const msgMap = {}
      res.data.forEach(m => { if (m.id) msgMap[m.content + "|" + m.role + "|" + m.time] = m })
      messages.forEach(msg => {
        const key = msg.content + "|" + msg.role + "|" + msg.time
        if (!msg.id && msgMap[key]) {
          msg.id = msgMap[key].id
          msg.satisfaction = msgMap[key].satisfaction
        }
      })
    }
  } catch (e) { /* ignore */ }
}

function scrollToBottom() {
  if (chatBodyRef.value) chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
}

watch(chatVisible, async (val) => { if (val) { await nextTick(); scrollToBottom() } })
</script>

<style scoped lang="scss">
.ai-chat-wrapper { position: fixed; right: 24px; bottom: 80px; z-index: 9999; }

.ai-chat-ball {
  width: 56px; height: 56px; border-radius: 50%;
  background: linear-gradient(135deg, rgba(255, 55, 29, 0.9), rgba(255, 85, 49, 0.9));
  box-shadow: 0 4px 16px rgba(255, 55, 29, 0.4);
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; color: #fff;
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  &:hover { transform: scale(1.08); box-shadow: 0 6px 24px rgba(255, 55, 29, 0.55); }
  &:active { transform: scale(0.95); }
}

.ai-chat-panel {
  width: 380px; height: 520px; background: #fff; border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12), 0 2px 8px rgba(0,0,0,0.08);
  display: flex; flex-direction: column; overflow: hidden;
  position: relative;
  transition: width 0.05s, height 0.05s;
}

// ---- resize handles ----
.resize-handle { position: absolute; z-index: 20; }
.resize-n {
  top: 0; left: 12px; right: 12px; height: 6px; cursor: n-resize;
}
.resize-w {
  top: 12px; left: 0; bottom: 12px; width: 6px; cursor: w-resize;
}
.resize-nw {
  top: 0; left: 0; width: 14px; height: 14px; cursor: nw-resize;
}

.ai-chat-sidebar {
  position: absolute; top: 0; left: 0; bottom: 0; width: 280px;
  background: #fff; z-index: 10; border-right: 1px solid #ebeef5;
  border-radius: 12px 0 0 12px;
  display: flex; flex-direction: column;
  box-shadow: 4px 0 20px rgba(0,0,0,0.08);
}
.sidebar-header {
  height: 56px; padding: 0 16px;
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(135deg, rgba(255,55,29,0.9), rgba(255,85,49,0.9));
  color: #fff; flex-shrink: 0; border-radius: 12px 0 0 0;
}
.sidebar-title { font-size: 15px; font-weight: 600; }
.sidebar-close { cursor: pointer; }
.sidebar-list { flex: 1; overflow-y: auto; padding: 8px;
  &::-webkit-scrollbar { width: 4px; }
  &::-webkit-scrollbar-thumb { background: #ccc; border-radius: 2px; }
}
.sidebar-empty { text-align: center; color: #c0c4cc; font-size: 13px; padding-top: 40px; }
.sidebar-divider {
  padding: 12px 12px 6px; font-size: 12px; color: #909399;
  font-weight: 500; user-select: none;
}
.sidebar-item {
  padding: 10px 12px; border-radius: 8px; cursor: pointer;
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 2px; transition: background 0.15s;
  &:hover { background: #f5f6fa; }
  &.active { background: rgba(255,55,29,0.08); }
}
.sidebar-item-info { flex: 1; min-width: 0; }
.sidebar-item-title { font-size: 13px; color: #303133; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sidebar-item-time { font-size: 11px; color: #c0c4cc; margin-top: 2px; }
.sidebar-item-delete {
  color: #c0c4cc; flex-shrink: 0; margin-left: 8px; cursor: pointer;
  &:hover { color: #f56c6c; }
}

.ai-chat-header {
  height: 56px; padding: 0 16px;
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(135deg, rgba(255,55,29,0.9), rgba(255,85,49,0.9));
  color: #fff; flex-shrink: 0;
  .header-left { display: flex; align-items: center; gap: 8px; }
  .header-title { font-size: 15px; font-weight: 600; }
  .header-status { font-size: 12px; display: flex; align-items: center; gap: 4px; opacity: 0.85; }
  .status-dot { width: 7px; height: 7px; border-radius: 50%; background: #67c23a; display: inline-block; }
  .header-right { display: flex; align-items: center; gap: 12px; }
  .header-icon { cursor: pointer; opacity: 0.8; transition: opacity 0.2s; &:hover { opacity: 1; } }
}

.ai-chat-body {
  flex: 1; overflow-y: auto; padding: 16px; background: #f5f6fa;
  &::-webkit-scrollbar { width: 4px; }
  &::-webkit-scrollbar-thumb { background: #ccc; border-radius: 2px; }
}

.welcome-area { text-align: center; padding-top: 40px; }
.welcome-icon { color: rgba(255,55,29,0.6); margin-bottom: 12px; }
.welcome-title { font-size: 17px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.welcome-desc { font-size: 13px; color: #909399; margin-bottom: 24px; }

.quick-questions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; padding: 0 8px; }
.quick-item {
  padding: 6px 14px; background: #fff; border: 1px solid #e4e7ed;
  border-radius: 16px; font-size: 12px; color: #606266; cursor: pointer;
  transition: all 0.2s; white-space: nowrap;
  &:hover { color: rgba(255,55,29,0.85); border-color: rgba(255,55,29,0.4); background: rgba(255,55,29,0.04); }
}

.message-item { display: flex; gap: 10px; margin-bottom: 16px; }
.message-item.message-user { flex-direction: row-reverse; }
.message-item.message-user .message-text {
  background: linear-gradient(135deg, rgba(255,55,29,0.9), rgba(255,85,49,0.9));
  color: #fff; border-radius: 12px 4px 12px 12px;
}
.message-item.message-ai .message-text { background: #fff; color: #303133; border-radius: 4px 12px 12px 12px; }
.message-avatar { flex-shrink: 0; }
.message-content { display: flex; flex-direction: column; max-width: 75%; position: relative; }
.message-text { padding: 10px 14px; font-size: 13px; line-height: 1.6; word-break: break-word; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.message-time { font-size: 11px; color: #c0c4cc; margin-top: 4px; padding: 0 4px; }
.message-user .message-time { text-align: right; }
.msg-feedback {
  cursor: pointer; color: #c0c4cc; padding: 2px; border-radius: 4px;
  &:hover { color: rgba(255,55,29,0.85); background: rgba(255,55,29,0.08); }
  &.active { color: rgba(255,55,29,0.85); }
}
.msg-actions { display: flex; gap: 6px; margin-top: 6px; }
.msg-copy, .msg-refresh {
  cursor: pointer; color: #c0c4cc; padding: 2px; border-radius: 4px;
  &:hover { color: rgba(255,55,29,0.85); background: rgba(255,55,29,0.08); }
}


// ---- markdown rendered content ----
.message-text :deep(h1) { font-size: 16px; font-weight: 700; margin: 12px 0 6px; line-height: 1.4; }
.message-text :deep(h2) { font-size: 15px; font-weight: 600; margin: 10px 0 4px; line-height: 1.4; }
.message-text :deep(h3) { font-size: 14px; font-weight: 600; margin: 8px 0 4px; line-height: 1.4; }
.message-text :deep(p) { margin: 4px 0; }
.message-text :deep(ul), .message-text :deep(ol) { margin: 4px 0; padding-left: 18px; }
.message-text :deep(li) { margin: 2px 0; }
.message-text :deep(strong) { font-weight: 600; }
.message-text :deep(code) {
  background: rgba(0,0,0,0.06); padding: 2px 5px; border-radius: 3px;
  font-size: 12px; font-family: 'Menlo', 'Consolas', monospace;
}
.message-text :deep(pre) {
  background: #f0f2f5; padding: 10px 12px; border-radius: 6px;
  overflow-x: auto; font-size: 12px; margin: 6px 0; line-height: 1.5;
}
.message-text :deep(pre code) { background: none; padding: 0; }
.message-text :deep(table) {
  width: 100%; border-collapse: collapse; margin: 8px 0; font-size: 12px;
}
.message-text :deep(th), .message-text :deep(td) {
  border: 1px solid #e4e7ed; padding: 6px 10px; text-align: left;
}
.message-text :deep(th) { background: #f5f6fa; font-weight: 600; }
.message-text :deep(blockquote) {
  border-left: 3px solid rgba(255,55,29,0.4); padding-left: 10px;
  margin: 6px 0; color: #909399;
}
.message-text :deep(hr) { border: none; border-top: 1px solid #ebeef5; margin: 10px 0; }
.message-text :deep(a) { color: rgba(255,55,29,0.85); text-decoration: underline; }
.message-user .message-text :deep(code) { background: rgba(255,255,255,0.2); }
.message-user .message-text :deep(pre) { background: rgba(0,0,0,0.15); }
.message-user .message-text :deep(th) { background: rgba(255,255,255,0.1); }
.message-user .message-text :deep(th), .message-user .message-text :deep(td) { border-color: rgba(255,255,255,0.25); }
.message-user .message-text :deep(blockquote) { border-left-color: rgba(255,255,255,0.4); color: rgba(255,255,255,0.85); }
.message-user .message-text :deep(a) { color: #fff; }

.typing-indicator { display: flex; gap: 4px; padding: 14px 18px; }
.typing-indicator span {
  width: 7px; height: 7px; border-radius: 50%; background: #c0c4cc;
  animation: typing 1.4s infinite ease-in-out both;
  &:nth-child(1) { animation-delay: 0s; }
  &:nth-child(2) { animation-delay: 0.16s; }
  &:nth-child(3) { animation-delay: 0.32s; }
}
@keyframes typing {
  0%,80%,100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.ai-chat-footer {
  padding: 10px 12px; border-top: 1px solid #eee; background: #fff;
  display: flex; align-items: flex-end; gap: 8px; flex-shrink: 0;
  :deep(.el-textarea__inner) {
    border-radius: 8px; font-size: 13px; line-height: 1.5; padding: 8px 12px; border-color: #e4e7ed;
    &:focus { border-color: rgba(255,55,29,0.5); }
  }
}

.send-btn {
  width: 38px; height: 38px; border-radius: 50%; padding: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255,55,29,0.85); border: none; flex-shrink: 0;
  &:hover { background: rgba(255,55,29,1); }
  &:disabled { background: #c0c4cc; }
}
.stop-btn { background: #f56c6c !important; &:hover { background: #e84848 !important; } }

.ball-fade-enter-active,.ball-fade-leave-active { transition: all 0.3s ease; }
.ball-fade-enter-from,.ball-fade-leave-to { opacity: 0; transform: scale(0.6); }
.panel-slide-enter-active { transition: all 0.35s cubic-bezier(0.4,0,0.2,1); }
.panel-slide-leave-active { transition: all 0.25s ease-in; }
.panel-slide-enter-from { opacity: 0; transform: translateY(20px) scale(0.95); }
.panel-slide-leave-to { opacity: 0; transform: translateY(10px) scale(0.9); }
</style>

$content = @"
<template>
  <div class="ai-chat-wrapper">
    <transition name="ball-fade">
      <div v-show="!chatVisible" class="ai-chat-ball" @click="openChat">
        <el-icon :size="26"><ChatDotRound /></el-icon>
      </div>
    </transition>

    <transition name="panel-slide">
      <div v-show="chatVisible" class="ai-chat-panel">
        <div class="ai-chat-sidebar" v-show="sidebarVisible">
          <div class="sidebar-header">
            <span class="sidebar-title">历史对话</span>
            <el-icon :size="18" class="sidebar-close" @click="sidebarVisible = false"><Close /></el-icon>
          </div>
          <div class="sidebar-list">
            <div
              v-for="conv in conversationList"
              :key="conv.id"
              :class="['sidebar-item', { active: conv.id === sessionId }]"
              @click="switchConversation(conv.id)"
            >
              <div class="sidebar-item-info">
                <div class="sidebar-item-title">{{ conv.title }}</div>
                <div class="sidebar-item-time">{{ conv.time }}</div>
              </div>
              <el-icon :size="14" class="sidebar-item-delete" @click.stop="deleteConversation(conv.id)"><Delete /></el-icon>
            </div>
            <div v-if="conversationList.length === 0" class="sidebar-empty">暂无历史对话</div>
          </div>
        </div>

        <div class="ai-chat-header">
          <div class="header-left">
            <el-icon :size="18" class="header-icon" @click="toggleSidebar" title="历史对话"><Expand /></el-icon>
            <el-icon :size="20"><Service /></el-icon>
            <span class="header-title">AI 智能客服</span>
            <span class="header-status"><span class="status-dot"></span>在线</span>
          </div>
          <div class="header-right">
            <el-icon :size="18" class="header-icon" @click="newConversation" title="新建会话"><Plus /></el-icon>
            <el-icon :size="18" class="header-icon" @click="clearChat" title="清空消息"><Delete /></el-icon>
            <el-icon :size="20" class="header-icon header-close" @click="closeChat"><Close /></el-icon>
          </div>
        </div>

        <div class="ai-chat-body" ref="chatBodyRef">
          <div v-if="messages.length === 0" class="welcome-area">
            <div class="welcome-icon"><el-icon :size="48"><Service /></el-icon></div>
            <div class="welcome-title">你好，我是AI智能客服</div>
            <div class="welcome-desc">有什么可以帮助你的吗？</div>
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
              <div v-if="msg.role === 'ai' && msg.content !== '__LOADING__' && !isLoading" class="msg-refresh" @click="regenerate(idx)"><el-icon :size="14"><Refresh /></el-icon></div>
            </div>
          </div>
        </div>

        <div class="ai-chat-footer">
          <el-input v-model="inputText" type="textarea" :rows="1" :autosize="{ minRows: 1, maxRows: 3 }" placeholder="输入你的问题..." resize="none" @keydown.enter.exact.prevent="sendMessage" />
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
"@
[System.IO.File]::WriteAllText("E:\codexop\damai\vue3\src\components\AiChat\index.vue", $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Part 1 done"

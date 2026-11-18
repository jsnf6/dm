<template>
  <div class="ai-chat-wrapper" v-if="!isOnChatPage">
    <div class="ai-chat-ball" @click.stop="goToChat" title="AI客服">
      <el-icon :size="26" class="ball-icon"><ChatDotRound /></el-icon>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ChatDotRound } from "@element-plus/icons-vue";

const router = useRouter();
const route = useRoute();

// 在AI客服页面本身隐藏悬浮球
const isOnChatPage = computed(() => route.path === "/aiChat/index");

function goToChat() {
  router.push("/aiChat/index").catch((err) => {
    console.warn("Router push failed, falling back to window.open:", err);
    window.open("/aiChat/index", "_blank");
  });
}
</script>

<style scoped>
.ai-chat-wrapper {
  position: fixed;
  right: 24px;
  bottom: 80px;
  z-index: 9999;
}
.ai-chat-ball {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: linear-gradient(
    135deg,
    rgba(255, 55, 29, 0.9),
    rgba(255, 85, 49, 0.9)
  );
  box-shadow: 0 4px 16px rgba(255, 55, 29, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #fff;
  transition: transform 0.2s, box-shadow 0.2s;
  user-select: none;
}
.ai-chat-ball:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 20px rgba(255, 55, 29, 0.5);
}
.ai-chat-ball:active {
  transform: scale(0.95);
}
/* 关键修复：让图标不拦截点击事件 */
.ball-icon {
  pointer-events: none;
}
</style>

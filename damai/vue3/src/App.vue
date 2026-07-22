<template>
  <router-view :key="route.fullPath"/>
  <AiChat />
</template>
<script setup>
import {useRoute, useRouter} from 'vue-router'
import AiChat from '@/components/AiChat/index.vue'
const route = useRoute();
</script>

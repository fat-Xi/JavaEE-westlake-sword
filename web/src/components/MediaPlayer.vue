<script setup>
// 第三方网页需使用供应商允许嵌入的地址；不使用外链跳转替代播放器。
defineProps({ src: String, title: String });
</script>
<template>
  <video
    v-if="
      /\.(mp4|webm)(\?.*)?$/i.test(src) ||
      /^\/api\/talks\/\d+\/replay$/.test(src)
    "
    :src="src"
    controls
    preload="none"
  ></video>
  <iframe
    v-else
    :src="src"
    :title="title || '会议视频'"
    class="media-frame"
    sandbox="allow-scripts allow-same-origin allow-presentation"
    allow="fullscreen; autoplay; encrypted-media"
    allowfullscreen
    referrerpolicy="no-referrer"
  ></iframe>
  <p class="muted">如无法播放，请联系管理员检查播放源及嵌入授权。</p>
</template>

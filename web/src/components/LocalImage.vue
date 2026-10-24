<script setup>
// 本地图片缩放后以位图内容保存到数据库；不上传可执行 SVG。
const props = defineProps({ modelValue: String });
const emit = defineEmits(["update:modelValue", "error"]);
async function pick(event) {
  const file = event.target.files[0];
  if (!file) return;
  try {
    if (
      !["image/jpeg", "image/png", "image/webp"].includes(file.type) ||
      file.size > 5 * 1024 * 1024
    )
      throw new Error("请选择 5MB 以内的 PNG、JPEG、WebP 图片");
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, 1200 / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    canvas
      .getContext("2d")
      .drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    emit("update:modelValue", canvas.toDataURL("image/png"));
  } catch (e) {
    emit("error", e.message || "图片读取失败");
  }
  event.target.value = "";
}
</script>
<template>
  <div class="image-upload">
    <label
      >本地图片 / Logo<input
        type="file"
        accept="image/png,image/jpeg,image/webp"
        @change="pick"
    /></label>
    <img v-if="modelValue" :src="modelValue" alt="所选图片预览" />
    <button
      v-if="modelValue"
      type="button"
      @click="emit('update:modelValue', '')"
    >
      移除图片
    </button>
    <small>选择后预览，保存表单时同步至数据库。</small>
  </div>
</template>

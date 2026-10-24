<script setup>
// 注册和个人主页共用字段，图片在浏览器缩小后保存为位图。
const props = defineProps({ model: Object });
const emit = defineEmits(["error"]);
async function avatar(event) {
  const file = event.target.files[0];
  if (!file) return;
  if (
    !["image/png", "image/jpeg", "image/webp"].includes(file.type) ||
    file.size > 5 * 1024 * 1024
  ) {
    emit("error", "请选择5MB以内的PNG、JPEG或WebP头像");
    return;
  }
  try {
    const bitmap = await createImageBitmap(file);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 160;
    const size = Math.min(bitmap.width, bitmap.height);
    canvas
      .getContext("2d")
      .drawImage(
        bitmap,
        (bitmap.width - size) / 2,
        (bitmap.height - size) / 2,
        size,
        size,
        0,
        0,
        160,
        160,
      );
    props.model.avatar = canvas.toDataURL("image/png");
    bitmap.close();
  } catch {
    emit("error", "图片无法读取，请更换头像");
  }
}
</script>
<template>
  <div class="profile-photo">
    <img
      class="avatar large"
      :src="model.avatar || '/avatar.svg'"
      alt="个人头像"
    />
    <label
      >上传头像<input
        type="file"
        accept="image/png,image/jpeg,image/webp"
        @change="avatar"
    /></label>
    <button v-if="model.avatar" type="button" @click="model.avatar = ''">
      恢复默认头像
    </button>
  </div>
  <div class="form-grid">
    <label>网名<input v-model="model.nick" required maxlength="40" /></label>
    <label
      >真实姓名<input v-model="model.realName" required maxlength="40"
    /></label>
    <label
      >性别<select v-model="model.gender">
        <option>未设置</option>
        <option>男</option>
        <option>女</option>
      </select></label
    >
    <label
      >邮箱<input v-model="model.email" type="email" required maxlength="120"
    /></label>
    <label
      >电话<input v-model="model.phone" type="tel" required maxlength="30"
    /></label>
    <label>单位<input v-model="model.company" maxlength="100" /></label>
    <label v-if="model.role === 'USER'"
      >兴趣标签<input
        v-model="model.tags"
        maxlength="200"
        placeholder="数据安全,云安全"
    /></label>
  </div>
</template>

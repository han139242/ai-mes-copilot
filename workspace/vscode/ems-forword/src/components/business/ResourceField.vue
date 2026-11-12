<script setup>
import { computed, ref } from 'vue';

// defineModel 接收父组件的 v-model：用户修改控件时，值会同步回通用资源页的表单对象。
const model = defineModel();

// field 描述字段类型和规则，options 是下拉选项；该组件只负责选择合适的 Element Plus 控件。
const props = defineProps({
  field: { type: Object, required: true },
  options: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  inputId: { type: String, default: undefined },
});
const keyword = ref('');
const filteredOptions = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase();
  return props.options.filter((item) =>
    String(item.searchLabel || item.label)
      .toLocaleLowerCase()
      .includes(query),
  );
});
function filterOptions(query) {
  keyword.value = query;
}
function resetSearch(visible) {
  if (!visible) {
    keyword.value = '';
  }
}
</script>

<template>
  <!-- select/reference 都使用下拉框；reference 的选项通常来自另一张业务表。 -->
  <el-select
    v-if="field.type === 'select' || field.type === 'reference'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    :multiple="field.multiple"
    :collapse-tags="field.multiple"
    :max-collapse-tags="2"
    :placeholder="field.placeholder || `请选择${field.label}`"
    :disabled="field.disabled"
    clearable
    filterable
    :filter-method="filterOptions"
    @visible-change="resetSearch"
  >
    <el-option
      v-for="item in filteredOptions"
      :key="String(item.value)"
      :label="item.label"
      :value="item.value"
      :disabled="item.disabled"
      :title="item.searchLabel || item.label"
    />
  </el-select>

  <!-- 数量、工时、比例等数字字段使用数字输入框，并读取最小值、精度和步长配置。 -->
  <el-input-number
    v-else-if="field.type === 'number'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    :min="field.min"
    :max="field.max"
    :precision="field.precision"
    :step="field.step || 1"
    :controls="!compact"
    controls-position="right"
    :placeholder="field.placeholder || `请输入${field.label}`"
    :disabled="field.disabled"
  />

  <!-- 日期、日期时间和时间字段分别使用对应选择器，并统一提交给后端的字符串格式。 -->
  <el-date-picker
    v-else-if="field.type === 'date'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    type="date"
    value-format="YYYY-MM-DD"
    format="YYYY-MM-DD"
    :placeholder="field.placeholder || `请选择${field.label}`"
    :disabled="field.disabled"
    :disabled-date="field.disabledDate"
  />

  <el-date-picker
    v-else-if="field.type === 'datetime'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    type="datetime"
    value-format="YYYY-MM-DDTHH:mm:ss"
    format="YYYY-MM-DD HH:mm"
    :placeholder="field.placeholder || `请选择${field.label}`"
    :disabled="field.disabled"
    :disabled-date="field.disabledDate"
  />

  <el-time-picker
    v-else-if="field.type === 'time'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    value-format="HH:mm:ss"
    format="HH:mm"
    :placeholder="field.placeholder || `请选择${field.label}`"
    :disabled="field.disabled"
  />

  <!-- 备注、描述等长文本使用多行输入框。 -->
  <el-input
    v-else-if="field.type === 'textarea'"
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    type="textarea"
    :rows="compact ? 2 : 3"
    :maxlength="field.maxLength || 500"
    show-word-limit
    resize="none"
    :placeholder="field.placeholder || `请输入${field.label}`"
    :disabled="field.disabled"
  />

  <!-- 其余字段默认使用普通输入框；password 类型会启用密码隐藏和显示按钮。 -->
  <el-input
    v-else
    v-model="model"
    :id="inputId"
    :aria-label="field.label"
    :type="field.type === 'password' ? 'password' : field.type === 'tel' ? 'tel' : 'text'"
    :show-password="field.type === 'password'"
    :maxlength="field.maxLength || 255"
    clearable
    :placeholder="field.placeholder || `请输入${field.label}`"
    :disabled="field.disabled"
  />
</template>

<style scoped>
/* 所有动态表单控件统一占满表单列宽。 */
.el-select,
.el-input,
.el-input-number,
.el-date-editor {
  width: 100%;
}
</style>

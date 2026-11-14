<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { privateAction, privateFileRequest } from '@/api/ai/private';
import { authRevision, getToken } from '@/utils/auth';

const props = defineProps({ modelValue: Boolean, document: Object, available: Boolean });
const emit = defineEmits(['update:modelValue', 'published']);
const fileInput = ref();
const file = ref(null);
const originalUrl = ref('');
const review = ref(null);
const prepared = ref(null);
const selected = ref([]);
const pageIndex = ref(0);
const outbound = ref(false);
const reviewed = ref(false);
const busy = ref('');
const error = ref('');
const currentPage = computed(() => review.value?.pages[pageIndex.value]);
const pageUrl = computed(
  () =>
    originalUrl.value +
    (currentPage.value && /\.pdf$/i.test(file.value?.name || '') ? '#page=' + currentPage.value.page : ''),
);
let owner;
let request;
let generation = 0;

function reset() {
  generation++;
  request?.abort();
  request = null;
  discard(prepared.value?.reviewId, owner);
  review.value = null;
  prepared.value = null;
  selected.value = [];
  if (originalUrl.value) URL.revokeObjectURL(originalUrl.value);
  originalUrl.value = '';
  file.value = null;
  busy.value = '';
  error.value = '';
  pageIndex.value = 0;
  outbound.value = false;
  reviewed.value = false;
}

watch(
  () => props.modelValue,
  (open) => {
    reset();
    if (open) owner = getToken();
  },
);
watch(authRevision, () => {
  if (props.modelValue && owner !== getToken()) close();
});
onBeforeUnmount(reset);

function close() {
  reset();
  emit('update:modelValue', false);
}

function discard(reviewId, token) {
  if (reviewId && token && token === getToken()) {
    privateAction('scans.discard', { reviewId }, { expectedAuthToken: token }).catch(() => {});
  }
}

function selectFile(event) {
  const next = event.target.files[0];
  event.target.value = '';
  if (!next) return;
  reset();
  if (!/\.(pdf|png|jpe?g)$/i.test(next.name) || !next.size || next.size > 8 * 1024 * 1024) {
    error.value = '请选择不超过 8 MB 的 PDF、PNG 或 JPEG';
    return;
  }
  file.value = next;
  send('prepare');
}

async function send(stage) {
  if (busy.value || !file.value || owner !== getToken()) return;
  const recognizing = stage === 'recognize';
  if (
    recognizing &&
    (!prepared.value || !outbound.value || !props.available || !selected.value.length || selected.value.length > 24)
  )
    return;
  if (stage === 'publish' && (!review.value || !reviewed.value)) return;
  const token = owner;
  const current = ++generation;
  const controller = new AbortController();
  request = controller;
  busy.value = stage;
  error.value = '';
  try {
    const fields =
      stage === 'prepare'
        ? props.document
          ? { documentId: props.document.id, baseVersion: props.document.version }
          : {}
        : {
            confirmed: 'true',
            reviewId: prepared.value.reviewId,
            pages: JSON.stringify(recognizing ? selected.value : review.value.pages),
          };
    const value = await privateFileRequest('/scans/' + stage, file.value, fields, {
      expectedAuthToken: token,
      signal: controller.signal,
      timeout: recognizing ? 260000 : 60000,
    });
    if (current !== generation || token !== getToken() || !props.modelValue) {
      if (stage !== 'publish') discard(value.reviewId, token);
      return;
    }
    if (stage === 'prepare') {
      // The service has checked the file signature before this link is made available.
      const type = /\.pdf$/i.test(file.value.name)
        ? 'application/pdf'
        : /\.png$/i.test(file.value.name)
          ? 'image/png'
          : 'image/jpeg';
      originalUrl.value = URL.createObjectURL(file.value.slice(0, file.value.size, type));
      prepared.value = value;
      selected.value = value.suggested.slice(0, 24);
    } else if (recognizing) {
      review.value = value;
    } else {
      review.value = null;
      prepared.value = null;
      emit('published', value);
      close();
    }
  } catch (failure) {
    if (current === generation && failure?.code !== 'ERR_CANCELED')
      error.value = failure.displayMessage || '扫描件操作未完成，请稍后再试';
  } finally {
    if (current === generation) {
      busy.value = '';
      request = null;
    }
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="document ? '用扫描件更新版本' : '扫描件识别'"
    width="min(720px, 94vw)"
    append-to-body
    :close-on-click-modal="false"
    :show-close="busy !== 'publish'"
    :close-on-press-escape="busy !== 'publish'"
    @update:model-value="
      (open) => {
        if (!open) close();
      }
    "
  >
    <div class="scan-import-body">
      <p v-if="error" class="scan-error" role="alert">{{ error }}</p>
      <template v-if="!review">
        <p v-if="!available">当前未配置云 OCR。已有文字层的 PDF 可以通过“上传资料”添加。</p>
        <template v-else>
          <p>支持 PDF、PNG 和 JPEG，每份最多 8 MB。先在本机检查页码，再选择要识别的页。</p>
          <input ref="fileInput" type="file" accept=".pdf,.png,.jpg,.jpeg" hidden @change="selectFile" />
          <div class="scan-row">
            <el-button :disabled="Boolean(busy)" @click="fileInput?.click()">选择扫描件</el-button>
            <strong v-if="file">{{ file.name }}</strong>
          </div>
          <p v-if="busy === 'prepare'" role="status">正在本机检查文件…</p>
          <template v-if="prepared">
            <a :href="pageUrl" target="_blank" rel="noopener noreferrer">打开本机原件 ↗</a>
            <label for="scan-page-selection">发送页码（已选 {{ selected.length }} 页，最多 24 页）</label>
            <el-select
              id="scan-page-selection"
              v-model="selected"
              multiple
              collapse-tags
              filterable
              :multiple-limit="24"
              :disabled="Boolean(busy)"
              @change="outbound = false"
            >
              <el-option
                v-for="page in prepared.pageCount"
                :key="page"
                :value="page"
                :label="'第 ' + page + ' 页' + (prepared.suggested.includes(page) ? ' · 无文字层' : '')"
              />
            </el-select>
            <p v-if="!prepared.suggested.length">每页均有可提取文字。若正文是扫描图，可手动勾选对应页。</p>
            <el-checkbox v-model="outbound" :disabled="Boolean(busy)" class="scan-consent">
              我有权将选中页面的渲染图发送到 DeepSeek 识别。
            </el-checkbox>
          </template>
          <p v-if="busy === 'recognize'" role="status">正在识别扫描页。关闭窗口后，已送达的识别可能继续。</p>
        </template>
      </template>
      <template v-else>
        <p>核对型号、批次号、数字和单位。修正后的文字会用于检索，并保留原始页码。</p>
        <div class="scan-row">
          <strong>{{ review.name }}</strong>
          <a :href="pageUrl" target="_blank" rel="noopener noreferrer">对照扫描原页 ↗</a>
        </div>
        <p v-if="review.nativePages">另有 {{ review.nativePages }} 页沿用本机提取的文字。</p>
        <div v-if="review.pages.length > 1" class="scan-row">
          <el-button :disabled="pageIndex === 0" @click="pageIndex--">上一页</el-button>
          <span>{{ pageIndex + 1 }} / {{ review.pages.length }}</span>
          <el-button :disabled="pageIndex + 1 === review.pages.length" @click="pageIndex++">下一页</el-button>
        </div>
        <label for="scan-page-text">原件第 {{ currentPage.page }} 页 · 识别文字</label>
        <el-input
          id="scan-page-text"
          v-model="currentPage.text"
          type="textarea"
          :rows="8"
          :disabled="busy === 'publish'"
          @input="reviewed = false"
        />
        <el-checkbox v-model="reviewed" :disabled="busy === 'publish'" class="scan-consent">
          我已核对识别内容，可以用于知识检索。
        </el-checkbox>
      </template>
    </div>
    <template #footer>
      <el-button :disabled="busy === 'publish'" @click="close">{{
        busy === 'recognize' ? '停止等待' : '关闭'
      }}</el-button>
      <el-button
        v-if="review"
        type="primary"
        :loading="busy === 'publish'"
        :disabled="!reviewed"
        @click="send('publish')"
        >确认入库</el-button
      >
      <el-button
        v-else-if="prepared"
        type="primary"
        :loading="busy === 'recognize'"
        :disabled="!outbound || !selected.length"
        @click="send('recognize')"
        >发送并识别</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.scan-import-body {
  display: grid;
  gap: 12px;
  color: var(--el-text-color-primary);
}
.scan-import-body p {
  margin: 0;
}
.scan-error {
  color: var(--el-color-danger-dark-2);
}
.scan-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  overflow-wrap: anywhere;
}
.scan-row a {
  color: var(--el-color-primary-dark-2);
  text-decoration: underline;
}
.scan-row .el-button {
  margin: 0;
}
.scan-consent {
  height: auto;
  min-height: 32px;
  white-space: normal;
}
.scan-consent :deep(.el-checkbox__label) {
  line-height: 1.5;
}
</style>

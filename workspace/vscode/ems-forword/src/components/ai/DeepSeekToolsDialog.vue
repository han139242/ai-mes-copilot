<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { privateAction, privateFileRequest } from '@/api/ai/private';
import { authRevision, getToken } from '@/utils/auth';
import { encodeRecording, supportsRecordingRate } from '@/utils/assistant-audio';
import recorderUrl from '@/utils/assistant-recorder.worklet.js?url&no-inline';

const props = defineProps({
  modelValue: Boolean,
  mode: { type: String, default: 'web' },
  answer: { type: String, default: '' },
  historyId: Number,
  conversation: { type: String, default: '' },
  applyTranscript: Function,
});
const emit = defineEmits(['update:modelValue']);
const available = ref(null);
const error = ref('');
const busy = ref(false);
const query = ref('');
const link = ref('');
const results = ref(null);
const page = ref(null);
const tab = ref('search');
const recording = ref(false);
const openingMic = ref(false);
const elapsed = ref(0);
const recordingFile = ref(null);
const transcript = ref('');
const speechUrl = ref('');
const audioPlayer = ref();
const upload = ref();
const AudioCaptureContext = globalThis.AudioContext || globalThis.webkitAudioContext;
const secureContext = globalThis.isSecureContext;
const voiceSupported = Boolean(
  secureContext && navigator.mediaDevices?.getUserMedia && globalThis.AudioWorkletNode && AudioCaptureContext,
);
const title = computed(() => ({ web: '公开资料', voice: '语音输入', read: '朗读答复' })[props.mode]);
const speech = computed(() => Array.from(props.answer.trim()).slice(0, 600).join(''));
const shortSpeech = computed(() => speech.value !== props.answer.trim());
let token;
let generation = 0;
let controller;
let capture;

function releaseMic() {
  if (!capture) return;
  clearInterval(capture.timer);
  clearTimeout(capture.startTimer);
  capture.stream?.getTracks().forEach((track) => track.stop());
  capture.node?.port.close();
  capture.node?.disconnect();
  capture.source?.disconnect();
  capture.context?.close().catch(() => {});
  capture = null;
}

function reset() {
  generation++;
  controller?.abort();
  controller = null;
  releaseMic();
  audioPlayer.value?.pause();
  if (speechUrl.value) URL.revokeObjectURL(speechUrl.value);
  speechUrl.value = '';
  available.value = null;
  error.value = '';
  busy.value = false;
  recording.value = false;
  openingMic.value = false;
  elapsed.value = 0;
  recordingFile.value = null;
  transcript.value = '';
  query.value = '';
  link.value = '';
  results.value = null;
  page.value = null;
  tab.value = 'search';
}

watch(
  () => [props.modelValue, props.mode],
  async ([open]) => {
    reset();
    if (!open) return;
    token = getToken();
    await run(
      (options) => privateAction('tools.status', {}, options),
      (result) => {
        available.value = result.available === true;
      },
    );
  },
);

watch([authRevision, () => props.conversation], () => {
  if (props.modelValue) close();
});
function checkOwner() {
  if (props.modelValue && token !== getToken()) close();
}
onMounted(() => {
  window.addEventListener('storage', checkOwner);
  window.addEventListener('focus', checkOwner);
  document.addEventListener('visibilitychange', checkOwner);
});
onBeforeUnmount(() => {
  window.removeEventListener('storage', checkOwner);
  window.removeEventListener('focus', checkOwner);
  document.removeEventListener('visibilitychange', checkOwner);
  reset();
});

function close() {
  reset();
  emit('update:modelValue', false);
}

async function run(operation, success) {
  if (busy.value || !props.modelValue || token !== getToken()) return;
  const current = ++generation;
  const request = new AbortController();
  controller = request;
  busy.value = true;
  error.value = '';
  try {
    const value = await operation({ expectedAuthToken: token, signal: request.signal });
    if (current === generation && props.modelValue && token === getToken()) success(value);
  } catch (failure) {
    if (current === generation && failure?.code !== 'ERR_CANCELED')
      error.value = failure.displayMessage || '操作未完成，请稍后再试';
  } finally {
    if (current === generation) {
      busy.value = false;
      controller = null;
    }
  }
}

function search() {
  if (!available.value || busy.value || !query.value.trim()) return;
  results.value = null;
  page.value = null;
  run(
    (options) => privateAction('tools.search', { query: query.value, confirmed: true }, { ...options, timeout: 55000 }),
    (value) => {
      results.value = value.results;
    },
  );
}

function readLink(url) {
  if (!available.value || busy.value || !url.trim()) return;
  page.value = null;
  run(
    (options) => privateAction('tools.fetch', { url: url.trim(), confirmed: true }, { ...options, timeout: 55000 }),
    (value) => {
      page.value = value;
    },
  );
}

async function startRecording() {
  if (!voiceSupported || !available.value || openingMic.value || recording.value || busy.value || token !== getToken())
    return;
  const current = generation;
  openingMic.value = true;
  error.value = '';
  transcript.value = '';
  recordingFile.value = null;
  elapsed.value = 0;
  try {
    let context;
    try {
      context = new AudioCaptureContext({ sampleRate: 16000 });
    } catch {
      context = new AudioCaptureContext();
    }
    const active = (capture = { context, chunks: [] });
    if (!supportsRecordingRate(context.sampleRate) || !context.audioWorklet) throw new Error();
    // Resume in the original click gesture. A late permission grant is disposed after cancellation.
    const resumed = context.resume();
    const microphone = navigator.mediaDevices
      .getUserMedia({ audio: { channelCount: 1 }, video: false })
      .then((stream) => {
        if (capture !== active || token !== getToken()) stream.getTracks().forEach((track) => track.stop());
        else active.stream = stream;
      });
    await Promise.race([
      Promise.all([resumed, microphone, context.audioWorklet.addModule(recorderUrl)]),
      new Promise((_, reject) => {
        active.startTimer = setTimeout(reject, 15000);
      }),
    ]);
    if (capture !== active) return;
    clearTimeout(active.startTimer);
    if (!active.stream || context.state !== 'running') throw new Error();
    active.source = context.createMediaStreamSource(active.stream);
    active.node = new AudioWorkletNode(context, 'mes-assistant-recorder', {
      channelCount: 1,
      channelCountMode: 'explicit',
    });
    active.node.port.onmessage = ({ data }) => {
      if (capture === active) active.chunks.push(data);
    };
    active.source.connect(active.node).connect(context.destination);
    recording.value = true;
    const started = performance.now();
    active.timer = setInterval(() => {
      elapsed.value = Math.min(30, Math.floor((performance.now() - started) / 1000));
      if (elapsed.value === 30) stopRecording();
    }, 200);
  } catch (failure) {
    if (current === generation) {
      releaseMic();
      error.value =
        failure.name === 'NotAllowedError'
          ? '麦克风未获许可。可选择 WAV 或继续键入。'
          : '无法启动麦克风，可选择 WAV 或继续键入。';
    }
  } finally {
    if (current === generation) openingMic.value = false;
  }
}

function stopRecording() {
  if (!capture) return;
  const { chunks, context } = capture;
  releaseMic();
  recording.value = false;
  try {
    recordingFile.value = new File([encodeRecording(chunks, context.sampleRate)], 'recording.wav', {
      type: 'audio/wav',
    });
  } catch (failure) {
    error.value = failure.message;
  }
}

function selectRecording(event) {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  if (!/\.wav$/i.test(file.name) || !file.size || file.size > 4 * 1024 * 1024) {
    error.value = '请选择不超过 4 MB 的 WAV 录音';
    return;
  }
  error.value = '';
  transcript.value = '';
  recordingFile.value = file;
}

function transcribe() {
  if (!recordingFile.value || !available.value || busy.value) return;
  run(
    (options) =>
      privateFileRequest('/tools/asr', recordingFile.value, { confirmed: 'true' }, { ...options, timeout: 55000 }),
    (value) => {
      transcript.value = value.text;
    },
  );
}

function insertTranscript() {
  if (!transcript.value.trim() || token !== getToken() || !props.modelValue) return;
  if (props.applyTranscript?.(transcript.value.trim()) === true) close();
}

function readAnswer() {
  if (!speech.value || !available.value || busy.value) return;
  const payload = { text: speech.value, confirmed: true };
  if (props.historyId) payload.historyId = props.historyId;
  run(
    (options) =>
      privateAction(props.historyId ? 'tools.tts.private' : 'tools.tts', payload, { ...options, timeout: 55000 }),
    (value) => {
      audioPlayer.value?.pause();
      if (speechUrl.value) URL.revokeObjectURL(speechUrl.value);
      const bytes = Uint8Array.from(atob(value.audio), (char) => char.charCodeAt(0));
      speechUrl.value = URL.createObjectURL(new Blob([bytes], { type: 'audio/wav' }));
    },
  );
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="min(640px, 94vw)"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="
      (open) => {
        if (!open) close();
      }
    "
  >
    <div class="deepseek-tool-body">
      <p v-if="error" class="tool-error" role="alert">{{ error }}</p>
      <p v-if="available === null && busy" role="status">正在检查工具状态…</p>
      <p v-else-if="available === false">DeepSeek 工具未启用。当前可以继续使用原有资料和业务查询。</p>
      <template v-else-if="available && mode === 'web'">
        <p class="tool-description">只填写可公开的关键词或链接。提交内容会发送到 DeepSeek，结果仅供查阅。</p>
        <el-radio-group v-model="tab" :disabled="busy" aria-label="公开资料查询方式">
          <el-radio-button value="search">搜索网页</el-radio-button>
          <el-radio-button value="link">读取链接</el-radio-button>
        </el-radio-group>
        <form v-if="tab === 'search'" class="tool-form" @submit.prevent="search">
          <el-input
            v-model="query"
            maxlength="128"
            aria-label="公开搜索关键词"
            placeholder="例如：厂商型号 设备说明书"
          />
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!query.trim()"
            >发送并搜索</el-button
          >
        </form>
        <form v-else class="tool-form" @submit.prevent="readLink(link)">
          <el-input v-model="link" maxlength="2048" aria-label="公开网页链接" placeholder="https://…" />
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!link.trim()">发送并读取</el-button>
        </form>
        <p v-if="tab === 'link'" class="tool-description">支持配置过的公开站点。带查询参数的链接可在浏览器打开。</p>
        <p v-if="results && !results.length" role="status">没有找到可显示的公开来源。</p>
        <ol v-if="tab === 'search' && results?.length" class="web-sources">
          <li v-for="source in results" :key="source.url">
            <a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.title }}</a>
            <small
              >{{ source.site }}<template v-if="source.date"> · {{ source.date.slice(0, 10) }}</template></small
            >
            <p v-if="source.excerpt">{{ source.excerpt }}</p>
            <el-button v-if="source.readable" text :disabled="busy" @click="readLink(source.url)">读取正文</el-button>
          </li>
        </ol>
        <article v-if="page" class="web-page">
          <a :href="page.url" target="_blank" rel="noopener noreferrer">{{ page.title }} ↗</a>
          <p v-if="page.truncated" class="tool-description">正文已截取，可打开原网页查看全文。</p>
          <pre>{{ page.text }}</pre>
        </article>
      </template>
      <template v-else-if="available && mode === 'voice'">
        <p class="tool-description">录音最长 30 秒。点转写才会发送录音，识别后请核对编号和数字。</p>
        <div class="tool-form">
          <el-button v-if="recording" type="primary" @click="stopRecording">结束录音 {{ elapsed }} / 30 秒</el-button>
          <el-button v-else :loading="openingMic" :disabled="!voiceSupported || busy" @click="startRecording"
            >开始录音</el-button
          >
          <el-button :disabled="recording || openingMic || busy" @click="upload?.click()">选择 WAV</el-button>
          <input ref="upload" type="file" accept=".wav,audio/wav" hidden @change="selectRecording" />
        </div>
        <small v-if="!voiceSupported">{{
          secureContext ? '当前浏览器不支持录音，可选择 WAV。' : '浏览器录音需要 HTTPS 或本机访问。'
        }}</small>
        <div v-if="recordingFile" class="tool-form">
          <span>{{ recordingFile.name }}</span>
          <el-button type="primary" :loading="busy" :disabled="recording" @click="transcribe">发送录音并转写</el-button>
        </div>
        <template v-if="transcript">
          <el-input v-model="transcript" type="textarea" :rows="3" maxlength="4000" aria-label="可编辑的转写结果" />
          <el-button :disabled="busy || !transcript.trim()" @click="insertTranscript">填入草稿</el-button>
        </template>
      </template>
      <template v-else-if="available && mode === 'read'">
        <p class="tool-description">
          确认后把下方文字发送到 DeepSeek 合成音频。<template v-if="shortSpeech">仅朗读答复前 600 字。</template>
        </p>
        <blockquote>{{ speech }}</blockquote>
        <el-button type="primary" :loading="busy" :disabled="!speech" @click="readAnswer">发送并合成语音</el-button>
        <audio v-if="speechUrl" ref="audioPlayer" :src="speechUrl" controls preload="metadata" aria-label="播放答复" />
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.deepseek-tool-body {
  display: grid;
  gap: 12px;
  min-width: 0;
  color: var(--el-text-color-primary);
}
.tool-description,
.tool-error,
.deepseek-tool-body p {
  margin: 0;
}
.tool-description {
  color: var(--el-text-color-regular);
}
.tool-error {
  color: var(--el-color-danger-dark-2);
}
.tool-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.tool-form .el-input {
  flex: 1 1 220px;
}
.tool-form .el-button {
  margin: 0;
}
.web-sources {
  display: grid;
  gap: 10px;
  padding: 0;
  margin: 0;
  list-style: none;
}
.web-sources li,
.web-page,
blockquote {
  min-width: 0;
  overflow-wrap: anywhere;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  margin: 0;
}
.web-sources small {
  display: block;
  margin: 4px 0;
  color: var(--el-text-color-regular);
}
.web-sources .el-button {
  margin: 4px 0 0;
}
.web-sources a,
.web-page a {
  color: var(--el-color-primary-dark-2);
  text-decoration: underline;
}
.web-page pre,
blockquote {
  white-space: pre-wrap;
  overflow: auto;
  max-height: 260px;
  font: inherit;
}
audio {
  width: 100%;
}
</style>

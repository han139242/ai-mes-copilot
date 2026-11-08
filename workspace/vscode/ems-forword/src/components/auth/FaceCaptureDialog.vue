<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { faceAction } from '@/api/ai/private';
import { getToken } from '@/utils/auth';

const props = defineProps({
  modelValue: Boolean,
  purpose: { type: String, default: 'login' },
  username: { type: String, default: '' },
  password: { type: String, default: '' },
});
const emit = defineEmits(['update:modelValue', 'verified']);
const video = ref(),
  consent = ref(false),
  busy = ref(false),
  error = ref(''),
  stage = ref('ready');
const frames = ref([]),
  challenge = ref(null),
  seconds = ref(0),
  guidance = ref('正在检测画面'),
  stable = ref(0);
const voice = ref(true);
const voiceAvailable = typeof window !== 'undefined' && 'speechSynthesis' in window;
let stream,
  timer,
  probeTimer,
  probeController,
  expiresAt = 0,
  attempt = 0,
  ownerToken = '',
  capturedAt = 0;
const isEnroll = computed(() => props.purpose === 'enroll');
const sequence = computed(() => challenge.value?.sequence || ['front', 'left', 'right']);
const currentPose = computed(() => sequence.value[Math.min(frames.value.length, 2)]);
const poseNames = { front: '正对摄像头', left: '向你的左侧轻转头', right: '向你的右侧轻转头' };
const title = computed(() => (isEnroll.value ? '录入本人人脸' : '人脸登录'));

function sayPose() {
  if (!voice.value || !voiceAvailable) return;
  window.speechSynthesis.cancel();
  const speech = new SpeechSynthesisUtterance(poseNames[currentPose.value] + '，请保持片刻');
  speech.lang = 'zh-CN';
  window.speechSynthesis.speak(speech);
}
function stopCamera() {
  clearInterval(timer);
  clearTimeout(probeTimer);
  probeController?.abort();
  probeController = null;
  document.removeEventListener('visibilitychange', pauseWhenHidden);
  stream?.getTracks().forEach((track) => track.stop());
  stream = null;
  if (video.value) video.value.srcObject = null;
  if (voiceAvailable) window.speechSynthesis.cancel();
}
function resetSession() {
  attempt += 1;
  stopCamera();
  frames.value = [];
  challenge.value = null;
  consent.value = false;
  error.value = '';
  stage.value = 'ready';
  busy.value = false;
  stable.value = 0;
}
function close() {
  resetSession();
  emit('update:modelValue', false);
}
function expire(message) {
  stopCamera();
  stage.value = 'expired';
  frames.value = [];
  stable.value = 0;
  error.value = message;
}
function pauseWhenHidden() {
  if (document.hidden && stage.value === 'capture') expire('已停止后台采集。回到页面后重新开始即可。');
}
async function getChallenge(currentAttempt) {
  const response = await faceAction(
    isEnroll.value ? 'challenge-enroll' : 'identify-challenge',
    isEnroll.value ? { password: props.password } : {},
    { expectedAuthToken: ownerToken },
  );
  if (currentAttempt !== attempt || !props.modelValue) return false;
  challenge.value = response.data;
  expiresAt = Date.now() + challenge.value.expiresIn * 1000;
  return true;
}
async function start() {
  if (busy.value || (isEnroll.value && !consent.value)) return;
  error.value = '';
  frames.value = [];
  stable.value = 0;
  stopCamera();
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    error.value = '当前浏览器无法使用摄像头。请使用本机地址或 HTTPS，也可以继续密码登录。';
    return;
  }
  const currentAttempt = ++attempt;
  ownerToken = getToken();
  busy.value = true;
  try {
    if (!(await getChallenge(currentAttempt))) return;
    const camera = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: 'user', width: { ideal: 960 }, height: { ideal: 720 } },
    });
    if (currentAttempt !== attempt || !props.modelValue) {
      camera.getTracks().forEach((track) => track.stop());
      return;
    }
    stream = camera;
    stage.value = 'capture';
    await nextTick();
    video.value.srcObject = stream;
    await video.value.play();
    if (expiresAt - Date.now() < 30000 && !(await getChallenge(currentAttempt))) return;
    if (currentAttempt !== attempt || !props.modelValue) return;
    capturedAt = Date.now();
    guidance.value = '面部保持居中，系统会自动采集';
    seconds.value = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
    timer = setInterval(() => {
      seconds.value = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
      if (!seconds.value) expire('本次认证已超时，重新开始即可。');
    }, 1000);
    document.addEventListener('visibilitychange', pauseWhenHidden);
    sayPose();
    scheduleProbe(currentAttempt, 0, 200);
  } catch (failure) {
    if (currentAttempt !== attempt || !props.modelValue) return;
    stopCamera();
    stage.value = 'ready';
    error.value =
      failure.name === 'NotAllowedError'
        ? '摄像头权限未开启。请在浏览器中允许后重试，或使用密码登录。'
        : failure.name === 'NotFoundError'
          ? '没有找到摄像头，请连接设备后重试，或使用密码登录。'
          : failure.displayMessage || '暂时无法开启摄像头或认证服务，请稍后重试。';
  } finally {
    if (currentAttempt === attempt) busy.value = false;
  }
}
function scheduleProbe(currentAttempt, failures = 0, delay = 800) {
  if (currentAttempt === attempt && props.modelValue && stage.value === 'capture')
    probeTimer = setTimeout(() => probe(currentAttempt, failures), delay);
}
function snapshotFrame() {
  if (!video.value?.videoWidth) return null;
  const canvas = document.createElement('canvas');
  canvas.width = Math.min(960, video.value.videoWidth);
  canvas.height = Math.round((video.value.videoHeight * canvas.width) / video.value.videoWidth);
  canvas.getContext('2d').drawImage(video.value, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.85);
}
async function probe(currentAttempt, failures) {
  if (currentAttempt !== attempt || !props.modelValue || stage.value !== 'capture') return;
  if (getToken() !== ownerToken) {
    expire('登录会话已变化，请关闭后重新开始。');
    return;
  }
  if (document.hidden) {
    pauseWhenHidden();
    return;
  }
  if (Date.now() >= expiresAt) {
    expire('本次认证已超时，重新开始即可。');
    return;
  }
  const frame = snapshotFrame();
  if (!frame) {
    scheduleProbe(currentAttempt);
    return;
  }
  probeController = new AbortController();
  const startedAt = Date.now();
  try {
    const response = await faceAction(
      isEnroll.value ? 'frame-enroll' : 'identify-frame',
      {
        challengeId: challenge.value.challengeId,
        step: frames.value.length,
        frame,
        ...(frames.value.length ? { referenceFrame: frames.value[0] } : {}),
      },
      { expectedAuthToken: ownerToken, signal: probeController.signal },
    );
    if (currentAttempt !== attempt || !props.modelValue || stage.value !== 'capture') return;
    guidance.value = response.data.message;
    stable.value = response.data.ready ? stable.value + 1 : 0;
    if (stable.value >= 2 && Date.now() - capturedAt >= 1000) {
      frames.value = [...frames.value, frame];
      stable.value = 0;
      capturedAt = Date.now();
      if (frames.value.length === 3) {
        stopCamera();
        stage.value = 'submitting';
        await submit();
        return;
      }
      guidance.value = '已完成上一步，请跟随新的动作提示';
      sayPose();
    }
    // Maintain an 800ms start-to-start interval; do not add 800ms after processing.
    scheduleProbe(currentAttempt, 0, Math.max(0, 800 - (Date.now() - startedAt)));
  } catch (failure) {
    if (currentAttempt !== attempt || !props.modelValue || stage.value !== 'capture') return;
    stable.value = 0;
    if (failure.response?.status === 429) {
      guidance.value = '检测服务忙，正在自动等待，请保持姿态';
      scheduleProbe(currentAttempt, 0, 1100 + Math.floor(Math.random() * 500));
      return;
    }
    if (failure.response?.status === 400 || failures >= 2) {
      expire(failure.displayMessage || '检测服务暂时不可用，请重新开始或使用密码登录。');
      return;
    }
    guidance.value = '正在重新连接检测服务，请保持姿态';
    scheduleProbe(currentAttempt, failures + 1);
  }
}
async function submit() {
  if (busy.value || frames.value.length !== 3) return;
  if (expiresAt <= Date.now()) {
    expire('本次认证已超时，请重新开始。');
    return;
  }
  busy.value = true;
  const currentAttempt = attempt;
  try {
    const response = await faceAction(
      isEnroll.value ? 'enroll' : 'identify-login',
      {
        challengeId: challenge.value.challengeId,
        frames: frames.value,
        ...(isEnroll.value ? { password: props.password, consent: consent.value } : {}),
      },
      { expectedAuthToken: ownerToken },
    );
    if (currentAttempt !== attempt || !props.modelValue) return;
    emit('verified', response);
    close();
  } catch (failure) {
    if (currentAttempt !== attempt) return;
    expire(failure.displayMessage || '本次认证未通过，请重新采集，也可以使用密码登录。');
  } finally {
    if (currentAttempt === attempt) busy.value = false;
  }
}
watch(
  () => props.modelValue,
  (open) => {
    if (!open) resetSession();
  },
);
watch(voice, (enabled) => {
  if (!enabled && voiceAvailable) window.speechSynthesis.cancel();
});
onBeforeUnmount(resetSession);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    class="face-capture-dialog"
    :close-on-click-modal="false"
    append-to-body
    @close="close"
  >
    <p class="face-intro">
      {{
        isEnroll
          ? '仅保存用于比对的人脸特征，默认不保存照片。'
          : '无需填写账号，系统将识别你已录入的人脸并登录对应账号。图像仅用于本次比对，不保存原图。'
      }}
    </p>
    <p class="face-boundary">
      跟随提示完成正面和两次轻转头，姿态与清晰度达标后自动采集。{{
        isEnroll ? '完成后自动录入。' : '完成后自动验证并登录。'
      }}密码登录始终保留。
    </p>
    <el-checkbox v-if="isEnroll" v-model="consent" :disabled="stage !== 'ready' && stage !== 'expired'"
      >我同意在本人的私有资料库保存人脸特征</el-checkbox
    >
    <el-checkbox v-if="voiceAvailable" v-model="voice">语音动作提示</el-checkbox>
    <div v-if="stage === 'capture'" class="face-view">
      <video ref="video" muted autoplay playsinline aria-label="摄像头实时预览" />
      <span class="face-guide" aria-hidden="true" /><span class="face-time">剩余 {{ seconds }} 秒</span>
    </div>
    <div v-if="stage === 'capture'" class="face-pose" role="status" aria-live="polite">
      <span>第 {{ frames.length + 1 }} / 3 步 · 自动采集</span><strong>{{ poseNames[currentPose] }}</strong>
      <p>{{ stable ? '请保持，正在确认画面稳定' : guidance }}</p>
    </div>
    <ol v-if="challenge" class="face-steps" aria-label="动作完成进度">
      <li
        v-for="(pose, index) in sequence"
        :key="pose"
        :class="{ done: index < frames.length, current: stage === 'capture' && index === frames.length }"
      >
        <span>{{ poseNames[pose] }}</span
        ><small>{{ stage === 'expired' ? '待重新采集' : index < frames.length ? '已完成' : stage === 'capture' && index === frames.length ? '进行中' : '待完成' }}</small>
      </li>
    </ol>
    <p v-if="stage === 'submitting'" class="face-pose" role="status">动作已完成，正在本机验证，请稍候。</p>
    <p v-if="error" class="face-error" role="alert">{{ error }}</p>
    <p v-if="!isEnroll && stage === 'ready'" class="face-help">尚未录入人脸时，请先用密码登录，在“账号安全”中录入。</p>
    <template #footer
      ><div class="face-footer-actions">
        <el-button @click="close">{{ stage === 'submitting' ? '关闭' : isEnroll ? '取消' : '返回密码登录' }}</el-button>
        <el-button
          v-if="stage === 'ready' || stage === 'expired'"
          type="primary"
          :loading="busy"
          :disabled="isEnroll && !consent"
          @click="start"
          ><AppIcon name="scan" :size="16" />{{ stage === 'expired' ? '重新开始' : '开始自动采集' }}</el-button
        >
      </div></template
    >
  </el-dialog>
</template>

<style scoped>
:global(.face-capture-dialog) {
  --el-dialog-margin-top: clamp(16px, 6vh, 48px);
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - clamp(16px, 6vh, 48px) - 16px);
}
:global(.face-capture-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
}
:global(.face-capture-dialog .el-dialog__header),
:global(.face-capture-dialog .el-dialog__footer) {
  flex-shrink: 0;
}
.face-steps {
  display: flex;
  gap: 12px;
  list-style: none;
  margin: 18px 0 0;
  padding: 0;
}
.face-steps li {
  flex: 1;
  min-width: 0;
  padding-top: 10px;
  border-top: 2px solid var(--mes-border);
  color: var(--mes-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.face-steps small {
  display: block;
}
.face-steps .current {
  border-color: var(--mes-primary);
  color: var(--mes-primary);
}
.face-steps .done {
  border-color: var(--mes-success);
  color: var(--mes-success);
}
.face-intro {
  margin: 0 0 8px;
  color: var(--mes-text);
  line-height: 1.8;
}
.face-boundary,
.face-help {
  margin: 8px 0 14px;
  font-size: 13px;
  color: var(--mes-text-secondary);
  line-height: 1.8;
}
.face-view {
  position: relative;
  margin-top: 16px;
  overflow: hidden;
  border-radius: 12px;
  background: var(--mes-sidebar-raised);
  height: clamp(180px, 32dvh, 280px);
}
.face-view video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  transform: scaleX(-1);
}
.face-guide {
  position: absolute;
  inset: 12% 25%;
  border: 2px dashed var(--mes-primary-border);
  border-radius: 48%;
  pointer-events: none;
}
.face-time {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 9px;
  border-radius: 6px;
  background: var(--mes-surface);
  color: var(--mes-text);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.face-pose {
  text-align: center;
  margin-top: 16px;
}
.face-pose span {
  display: block;
  font-size: 12px;
  color: var(--mes-text-secondary);
}
.face-pose strong {
  display: block;
  margin-top: 6px;
  font-size: 20px;
  color: var(--mes-text);
}
.face-pose p {
  margin: 8px 0;
  color: var(--mes-text-secondary);
  font-size: 13px;
}
.face-error {
  padding: 10px 12px;
  background: var(--mes-danger-soft);
  color: var(--mes-danger);
  border-radius: 8px;
  line-height: 1.7;
}
.face-footer-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
.face-footer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
@media (max-width: 800px), (pointer: coarse) {
  .face-footer-actions :deep(.el-button),
  :global(.face-capture-dialog .el-dialog__headerbtn),
  :global(.face-capture-dialog .el-checkbox) {
    min-width: var(--mes-touch-target);
    min-height: var(--mes-touch-target);
  }
}
@media (max-width: 600px) {
  .face-pose strong {
    font-size: 18px;
  }
}
</style>

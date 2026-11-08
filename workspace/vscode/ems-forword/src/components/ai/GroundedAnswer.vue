<script setup>
import { computed } from 'vue';
import { answerTokens } from '@/utils/private-knowledge';
const props = defineProps({ text: { type: String, default: '' }, citations: { type: Array, default: () => [] } });
const emit = defineEmits(['source']);
const tokens = computed(() => answerTokens(props.text));
function citation(id) {
  return props.citations.find((item) => item.id === id);
}
</script>

<template>
  <div class="grounded-answer">
    <template v-for="(token, index) in tokens" :key="index">
      <button
        v-if="token.kind === 'citation' && citation(token.id)"
        type="button"
        class="inline-source"
        :aria-label="`查看来源 ${token.id}：${citation(token.id).name}`"
        @click="emit('source', citation(token.id))"
      >
        {{ token.text }}
      </button>
      <strong v-else-if="token.kind === 'strong'">{{ token.text }}</strong>
      <span v-else>{{ token.text }}</span>
    </template>
  </div>
</template>

<style scoped>
.grounded-answer {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.85;
  max-width: 74ch;
  color: var(--mes-text);
}
.inline-source {
  display: inline;
  border: 0;
  padding: 0 3px;
  margin: 0 1px;
  border-radius: 3px;
  background: var(--mes-primary-soft);
  color: var(--mes-primary);
  font: inherit;
  font-size: 0.87em;
  cursor: pointer;
  vertical-align: baseline;
}
.inline-source:hover {
  background: var(--mes-primary-border);
}
.inline-source:focus-visible {
  outline: 2px solid var(--mes-primary);
  outline-offset: 3px;
}
</style>

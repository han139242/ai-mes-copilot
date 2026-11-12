<script setup>
import { computed } from 'vue';
import { answerBlocks } from '@/utils/private-knowledge';
import AnswerInline from './AnswerInline.vue';
const props = defineProps({ text: { type: String, default: '' }, citations: { type: Array, default: () => [] } });
const emit = defineEmits(['source']);
const blocks = computed(() => answerBlocks(props.text));
</script>

<template>
  <div class="grounded-answer">
    <template v-for="(block, index) in blocks" :key="index">
      <h4 v-if="block.kind === 'heading'">
        <AnswerInline :text="block.text" :citations="citations" @source="emit('source', $event)" />
      </h4>
      <ul v-else-if="block.kind === 'list'">
        <li v-for="(item, i) in block.items" :key="i">
          <AnswerInline :text="item" :citations="citations" @source="emit('source', $event)" />
        </li>
      </ul>
      <ol v-else-if="block.kind === 'ordered'" :start="block.start">
        <li v-for="(item, i) in block.items" :key="i">
          <AnswerInline :text="item" :citations="citations" @source="emit('source', $event)" />
        </li>
      </ol>
      <div v-else-if="block.kind === 'table'" class="answer-table" tabindex="0" role="region" aria-label="回答数据表">
        <table>
          <thead>
            <tr>
              <th v-for="(cell, c) in block.headers" :key="c" scope="col">
                <AnswerInline :text="cell" :citations="citations" @source="emit('source', $event)" />
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, r) in block.rows" :key="r">
              <td v-for="(cell, c) in row" :key="c">
                <AnswerInline :text="cell" :citations="citations" @source="emit('source', $event)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <pre v-else-if="block.kind === 'code'"><code>{{ block.text }}</code></pre>
      <p v-else><AnswerInline :text="block.text" :citations="citations" @source="emit('source', $event)" /></p>
    </template>
  </div>
</template>

<style scoped>
.grounded-answer {
  overflow-wrap: anywhere;
  line-height: 1.85;
  max-width: 74ch;
  color: var(--mes-text);
}
.grounded-answer > :first-child {
  margin-top: 0;
}
.grounded-answer > :last-child {
  margin-bottom: 0;
}
p {
  white-space: pre-wrap;
  margin: 0 0 16px;
}
h4 {
  font-size: 15px;
  line-height: 1.8;
  font-weight: 600;
  margin: 24px 0 10px;
}
ul,
ol {
  padding-left: 22px;
  margin: 10px 0 18px;
}
li {
  padding-left: 4px;
  margin: 6px 0;
  white-space: pre-wrap;
}
li::marker {
  color: var(--mes-text-muted);
}
.answer-table {
  overflow-x: auto;
  margin: 16px 0 22px;
  overscroll-behavior: contain;
}
table {
  border-collapse: collapse;
  min-width: 100%;
  font-size: 13px;
  line-height: 1.7;
}
th,
td {
  padding: 11px 14px;
  text-align: left;
  border-bottom: 1px solid var(--mes-border);
  vertical-align: top;
  min-width: 90px;
  max-width: 340px;
}
th {
  background: var(--mes-surface-muted);
  font-weight: 600;
  color: var(--mes-text-secondary);
}
pre {
  max-width: 100%;
  overflow-x: auto;
  white-space: pre;
  line-height: 1.7;
  font-size: 13px;
  padding: 16px;
  background: var(--mes-surface-muted);
  border-radius: var(--mes-radius-sm);
}
</style>

<script setup>
import { computed, onMounted, ref } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { getCreateOrderOptions, previewStructuredCommand } from '@/api/ai/agent';

/*
 * 创建生产订单表单：助手说「请补充产品、客户、数量、交期」时，直接把可选值摊开成下拉，
 * 选完点生成，后端按表单值做预览并给出确认卡片，用户不用再手打编码和日期。
 * 这里只负责收集四项入参，真正建单仍然要用户在预览卡片上确认。
 */
const props = defineProps({
  // 会话ID带上，预览记录才能挂到当前会话，刷新后还能查回命令状态。
  conversationId: { type: [String, Number], default: null },
  disabled: { type: Boolean, default: false },
});

const emit = defineEmits(['previewed', 'error']);

const loading = ref(false);
const submitting = ref(false);
const errorMessage = ref('');
const products = ref([]);
const customers = ref([]);
const productCode = ref('');
const customerCode = ref('');
const orderQuantity = ref('1');
const deliveryDate = ref('');

const selectedProduct = computed(() => products.value.find((item) => item.productCode === productCode.value) || null);
const canSubmit = computed(
  () =>
    Boolean(productCode.value) &&
    Boolean(customerCode.value) &&
    Boolean(deliveryDate.value) &&
    !submitting.value &&
    !props.disabled,
);

// 交期默认给到「下一天」：演示时点开日期框就不用从当月第一天翻起。
function defaultDeliveryDate() {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

async function loadOptions() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await getCreateOrderOptions();
    const data = response?.data || {};
    products.value = Array.isArray(data.products) ? data.products : [];
    customers.value = Array.isArray(data.customers) ? data.customers : [];
    // 只有唯一可选值时代填，省掉一次点击；多个候选仍然让用户自己选，不替他决定。
    if (products.value.length === 1) {
      productCode.value = products.value[0].productCode;
    }
    if (customers.value.length === 1) {
      customerCode.value = customers.value[0].customerCode;
    }
    if (!products.value.length || !customers.value.length) {
      errorMessage.value = '没有启用状态的产品或客户，请先在主数据里维护。';
    }
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '可选值加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (!canSubmit.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    const response = await previewStructuredCommand({
      action: 'CREATE_PRODUCTION_ORDER',
      productCode: productCode.value,
      customerCode: customerCode.value,
      orderQuantity: String(orderQuantity.value).trim(),
      deliveryDate: deliveryDate.value,
      conversationId: props.conversationId || undefined,
      source: 'WORKBENCH',
    });
    const command = response?.data || {};
    if (!command.confirmationId) {
      errorMessage.value = '预览没有生成确认凭据，请稍后重试。';
      return;
    }
    emit('previewed', command);
  } catch (error) {
    const message = error?.displayMessage || error?.data?.message || '生成预览失败，请稍后重试。';
    errorMessage.value = message;
    emit('error', message);
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  deliveryDate.value = defaultDeliveryDate();
  loadOptions();
});
</script>

<template>
  <div class="create-order-form">
    <header class="form-head">
      <AppIcon name="plus" :size="13" />
      <span>填写创建生产订单</span>
      <span class="form-hint">选好下面四项，生成预览后确认才会建单</span>
    </header>

    <div v-if="loading" class="form-state">正在加载产品与客户…</div>

    <div v-else class="form-grid">
      <label class="form-field">
        <span class="field-label">产品</span>
        <el-select v-model="productCode" size="small" filterable placeholder="选择产品" :disabled="submitting">
          <el-option
            v-for="item in products"
            :key="item.productCode"
            :label="`${item.productCode} ${item.productName || ''}`.trim()"
            :value="item.productCode"
          />
        </el-select>
      </label>

      <label class="form-field">
        <span class="field-label">客户</span>
        <el-select v-model="customerCode" size="small" filterable placeholder="选择客户" :disabled="submitting">
          <el-option
            v-for="item in customers"
            :key="item.customerCode"
            :label="`${item.customerCode} ${item.customerName || ''}`.trim()"
            :value="item.customerCode"
          />
        </el-select>
      </label>

      <label class="form-field is-narrow">
        <span class="field-label">数量（台）</span>
        <el-input
          v-model="orderQuantity"
          size="small"
          type="number"
          min="1"
          step="1"
          placeholder="数量"
          :disabled="submitting"
        />
      </label>

      <label class="form-field">
        <span class="field-label">交期</span>
        <el-date-picker
          v-model="deliveryDate"
          size="small"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择交期"
          :disabled="submitting"
          style="width: 100%"
        />
      </label>
    </div>

    <p v-if="selectedProduct && selectedProduct.productModel" class="form-note">
      产品型号 {{ selectedProduct.productModel }}
    </p>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

    <footer class="form-foot">
      <button class="form-submit" type="button" :disabled="!canSubmit" @click="submit">
        <AppIcon name="play" :size="11" />
        <span>{{ submitting ? '生成中…' : '生成预览' }}</span>
      </button>
      <span class="form-safe">只创建草稿，不会自动排产，也不会改动库存。</span>
    </footer>
  </div>
</template>

<style scoped>
.create-order-form {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

.form-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--mes-text-primary, var(--mes-text));
  font-size: 12.5px;
  font-weight: 600;
}

.form-hint {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
  font-weight: 400;
}

.form-state {
  padding: 10px 0 4px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
}

.form-grid {
  display: grid;
  margin-top: 8px;
  gap: 8px 10px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-field.is-narrow {
  max-width: 130px;
}

.field-label {
  color: var(--mes-text-secondary, #646a73);
  font-size: 11.5px;
}

.form-note {
  margin: 6px 0 0;
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
}

.form-error {
  margin: 8px 0 0;
  color: var(--mes-danger, #d83931);
  font-size: 11.5px;
}

.form-foot {
  display: flex;
  align-items: center;
  margin-top: 10px;
  gap: 10px;
  flex-wrap: wrap;
}

.form-submit {
  display: inline-flex;
  align-items: center;
  padding: 5px 12px;
  color: #fff;
  font-size: 12px;
  border: 1px solid var(--mes-primary, #1d6ef2);
  border-radius: 5px;
  background: var(--mes-primary, #1d6ef2);
  cursor: pointer;
  gap: 5px;
}

.form-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-safe {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
}
</style>

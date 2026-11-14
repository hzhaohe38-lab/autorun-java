<template>
  <div class="flex-1 flex items-center bg-cyan-500/10 border border-cyan-400/20 rounded-md px-3 py-2 min-w-0">
    <input
      :value="modelValue"
      type="number"
      step="1"
      min="0"
      inputmode="numeric"
      class="w-full min-w-0 bg-transparent text-center text-sm font-mono text-cyan-200 outline-none"
      @change="emitValue"
    />
    <span class="text-[10px] text-cyan-500/45 pl-1 italic shrink-0">米</span>
    <span
      v-if="label"
      class="text-[9px] text-cyan-500/50 italic shrink-0 pl-2 ml-1.5 border-l border-cyan-400/20"
    >{{ label }}</span>
  </div>
</template>

<script setup>
import { nextTick } from 'vue';

const props = defineProps({
  modelValue: { type: Number, default: null },
  label: { type: String, default: '' },
});
const emit = defineEmits(['update:modelValue']);

const emitValue = async (event) => {
  const el = event.target;
  emit('update:modelValue', Number(el.value));
  // The parent may clamp this back to the value it already held (e.g. 8000 →
  // 6001 when 6001 was already the max). The reactive value is then unchanged,
  // so Vue never patches the DOM and the raw typed text would linger. Write the
  // canonical value back by hand once the parent has settled.
  await nextTick();
  el.value = props.modelValue ?? '';
};
</script>

<style scoped>
input[type='number'] {
  -moz-appearance: textfield;
  appearance: textfield;
}

input[type='number']::-webkit-outer-spin-button,
input[type='number']::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
</style>

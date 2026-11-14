<template>
  <div class="h-full min-h-0 flex flex-col bg-transparent overflow-hidden">
    <AppHeader ref="appHeaderRef" :scrolled="headerCompact" />

    <div class="flex-1 flex flex-col min-h-0 w-full mx-auto p-0 relative bg-transparent">
      <main
        ref="mainScrollRef"
        class="main-scroll-area relative overflow-y-auto w-full box-border px-4"
        :style="{
          paddingTop: `${headerHeight}px`,
          paddingBottom: `${bottomBarOverlayHeight + BOTTOM_BAR_CLEARANCE_GAP}px`,
        }"
        @scroll.passive="handleMainScroll"
      >
        <keep-alive>
          <RunRecords v-if="activeKey === 'records'" :key="'records'" />
          <Club v-else-if="activeKey === 'club'" :key="'club'" />
          <SubmitRun
            v-else-if="activeKey === 'submit'"
            :key="'submit'"
            @submitted="handleRunSubmitted"
          />
          <MyPage v-else-if="activeKey === 'my'" :key="'my'" />
        </keep-alive>
      </main>
    </div>

    <BottomTabBar
      ref="bottomBarRef"
      :active="activeKey"
      @update:active="setActiveKey"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch, provide, inject } from 'vue';
import { onDataChanged } from '@/utils/appEvents';
import RunRecords from '@/components/RunRecords.vue';
import Club from '@/components/Club.vue';
import SubmitRun from '@/components/SubmitRun.vue';
import AppHeader from '@/components/layout/AppHeader.vue';
import BottomTabBar from '@/components/layout/BottomTabBar.vue';
import MyPage from '@/views/MyPage.vue';
import { useDataStore } from '@/composables/useDataStore';
import { useApiRequestGate } from '@/composables/useApiRequestGate';
import { getViewportMetrics } from '@/utils/viewport';

const { fetchUserData, activeTab, userInfo, token, studentId } = useDataStore();
const { waitForIdle } = useApiRequestGate();
const rootShowMessage = inject('showMessage', null);

const appHeaderRef = ref(null);
const bottomBarRef = ref(null);
const mainScrollRef = ref(null);
const HEADER_RESERVED_SPACE = 56;
const DEFAULT_BOTTOM_BAR_OVERLAY_HEIGHT = 96;
const BOTTOM_BAR_CLEARANCE_GAP = 12;
const headerHeight = ref(HEADER_RESERVED_SPACE);
const bottomBarOverlayHeight = ref(DEFAULT_BOTTOM_BAR_OVERLAY_HEIGHT);
const headerCompact = ref(false);
// Persisted `activeTab` may hold a value that no longer has a tab (e.g. a stale
// 'chat' from before the message page was removed). Anything outside this list
// would match no branch in <keep-alive> and render a blank main area.
const VALID_TABS = ['club', 'records', 'submit', 'my'];
const resolveValidTab = (key) => (VALID_TABS.includes(key) ? key : 'submit');
const activeKey = ref(resolveValidTab(activeTab.value));
let homeMeasureFrame = 0;

function updateHeaderCompact(top) {
  const next = Number(top) > 6;
  if (next === headerCompact.value) return false;
  headerCompact.value = next;
  return true;
}

function handleMainScroll(event) {
  const top = event?.target?.scrollTop || 0;
  updateHeaderCompact(top);
}

function setActiveKey(key) {
  if (!key || key === activeKey.value) return;
  activeKey.value = resolveValidTab(key);
  activeTab.value = activeKey.value;
}

function measureHeights() {
  const bottomEl = bottomBarRef.value && (bottomBarRef.value.$el || bottomBarRef.value);
  headerHeight.value = HEADER_RESERVED_SPACE;

  if (bottomEl?.getBoundingClientRect) {
    const rect = bottomEl.getBoundingClientRect();
    const { visibleBottom } = getViewportMetrics();
    const overlayHeight = Math.max(0, Math.ceil(visibleBottom - rect.top));
    bottomBarOverlayHeight.value = overlayHeight || DEFAULT_BOTTOM_BAR_OVERLAY_HEIGHT;
  }
}

function scheduleMeasureHeights() {
  if (homeMeasureFrame) cancelAnimationFrame(homeMeasureFrame);
  homeMeasureFrame = requestAnimationFrame(() => {
    measureHeights();
    homeMeasureFrame = 0;
  });
}

function showMessage(message, type = 'info') {
  if (appHeaderRef.value?.show) {
    appHeaderRef.value.show(message, type);
    return;
  }

  if (typeof rootShowMessage === 'function') {
    rootShowMessage(message, type);
  }
}

async function refreshUserData(options = { background: true }) {
  if (!userInfo.value) return true;

  const result = await fetchUserData(options);
  if (result?.ok) return true;

  if (result?.reason === 'network_error') {
    showMessage('用户数据刷新失败', 'warning');
    return false;
  }

  showMessage(result?.message || '登录状态校验失败', 'error');
  return false;
}

// Both engines keep their own copy of the session token (sign tasks, auto-run
// config) and used to learn about a new one only when something was saved. After
// a re-login they would keep submitting with the expired token indefinitely.
async function syncEngineTokens() {
  const t = token.value;
  const sid = studentId.value;
  if (!t || !sid) return;
  const body = JSON.stringify({ studentId: sid, token: t });
  const opts = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body };
  await Promise.allSettled([
    fetch('/api/auto-sign/token', opts),
    fetch('/api/auto-run/token', opts),
  ]);
}

async function initializePage() {
  await refreshUserData({ background: false });
  await waitForIdle();
  await syncEngineTokens();
}

watch(token, () => {
  syncEngineTokens().catch(() => {});
});

// Auto-run and auto-sign finish on the server with no client event, so the
// completion stats (里程 / 次数 / 俱乐部) would stay stale until the app is
// restarted. Poll while visible and refresh immediately when the app returns to
// the foreground.
let statsTimer = null;
let stopDataWatch = null;

async function refreshStats() {
  if (document.visibilityState !== 'visible' || !userInfo.value) return;
  await fetchUserData({ background: true });
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    refreshStats().catch(() => {});
  }
}

async function handleRunSubmitted() {
  await refreshUserData({ background: true });
}

provide('showMessage', showMessage);

watch(
  activeKey,
  async () => {
    await nextTick();
    updateHeaderCompact(mainScrollRef.value?.scrollTop || 0);
    scheduleMeasureHeights();
  },
  { flush: 'post' },
);

onMounted(() => {
  initializePage().catch(() => {
    showMessage('用户数据刷新失败', 'warning');
  });

  scheduleMeasureHeights();
  window.addEventListener('resize', scheduleMeasureHeights);
  window.addEventListener('orientationchange', scheduleMeasureHeights);
  window.visualViewport?.addEventListener('resize', scheduleMeasureHeights);
  window.visualViewport?.addEventListener('scroll', scheduleMeasureHeights);

  // 服务端执行的任务（定时跑步、签到签退）不会给前端发事件，只能靠轮询兜底。
  // 20 秒足够快，又不至于把上游接口打爆；真正完成的那一刻由 appEvents 的
  // 广播立即触发刷新，这里只是保底。
  statsTimer = setInterval(() => {
    refreshStats().catch(() => {});
  }, 20000);
  document.addEventListener('visibilitychange', handleVisibilityChange);

  // 任何一次确认成功的动作（手动跑步、定时跑步、签到、签退）都会广播一次，
  // 收到就立刻重拉用户数据，让里程 / 次数 / 俱乐部统计当场更新。
  stopDataWatch = onDataChanged((reason) => {
    refreshUserData({ background: true }).catch(() => {});
  });

  nextTick(() => {
    updateHeaderCompact(mainScrollRef.value?.scrollTop || 0);
  });
});

onUnmounted(() => {
  window.removeEventListener('resize', scheduleMeasureHeights);
  window.removeEventListener('orientationchange', scheduleMeasureHeights);
  window.visualViewport?.removeEventListener('resize', scheduleMeasureHeights);
  window.visualViewport?.removeEventListener('scroll', scheduleMeasureHeights);

  if (statsTimer) clearInterval(statsTimer);
  if (stopDataWatch) stopDataWatch();
  document.removeEventListener('visibilitychange', handleVisibilityChange);

  if (homeMeasureFrame) {
    cancelAnimationFrame(homeMeasureFrame);
    homeMeasureFrame = 0;
  }
});
</script>

<style scoped>
.main-scroll-area {
  flex: 1 1 auto;
  min-height: 0;
  -webkit-overflow-scrolling: touch;
}
</style>

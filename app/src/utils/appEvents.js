/**
 * 全局数据变更通知。
 *
 * 定时跑步和自动签到都是服务端执行的，前端收不到任何回调——它们跑完了，
 * 但"管理"页顶部的里程 / 次数 / 俱乐部次数还是旧值，用户必须杀掉进程重进
 * 才能看到。手动提交跑步走的是组件事件，定时任务走不到那条路。
 *
 * 所以约定一个 window 事件：任何一次确认成功的动作（手动跑步、定时跑步、
 * 签到、签退）都发一次，HomePage 收到就重新拉一遍用户数据。
 */
export const DATA_CHANGED = 'app:data-changed';

/** @param {string} reason 便于调试：'run' | 'auto-run' | 'sign' | 'sign-back' */
export function notifyDataChanged(reason = '') {
  try {
    window.dispatchEvent(new CustomEvent(DATA_CHANGED, { detail: { reason } }));
  } catch (e) {
    /* 非浏览器环境忽略 */
  }
}

/** @returns {() => void} 取消监听 */
export function onDataChanged(handler) {
  const wrapped = (e) => handler(e?.detail?.reason || '');
  window.addEventListener(DATA_CHANGED, wrapped);
  return () => window.removeEventListener(DATA_CHANGED, wrapped);
}

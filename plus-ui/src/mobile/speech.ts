import { ref } from 'vue';
import request from '@/utils/request';

/**
 * 语音转文字：优先浏览器 Web Speech API（中文），不支持时回退到服务端语音识别（/api/ui/speech，需配置 lsmom.speech.url）；
 * 两者都不可用时隐藏语音按钮。
 */
export type SpeechMode = 'browser' | 'server' | 'none';
export const speechMode = ref<SpeechMode | null>(null);

const Recognition = () => (globalThis as any).SpeechRecognition ?? (globalThis as any).webkitSpeechRecognition;

let probing: Promise<SpeechMode> | null = null;
export function detectSpeech(): Promise<SpeechMode> {
  if (speechMode.value) return Promise.resolve(speechMode.value);
  probing ??= (async () => {
    let m: SpeechMode = 'none';
    if (Recognition()) m = 'browser';
    else if (typeof MediaRecorder !== 'undefined' && navigator.mediaDevices?.getUserMedia) {
      try {
        const r: any = await request({ url: '/api/ui/speech', method: 'get', headers: { silentError: true } });
        if (r?.data?.enabled) m = 'server';
      } catch {
        /* 服务端不可用 */
      }
    }
    speechMode.value = m;
    return m;
  })();
  return probing;
}

export interface Listening {
  stop: () => void;
}

/** 开始听写：浏览器识别或录音（松开时 stop，识别结果经 onText 返回） */
export async function listen(onText: (t: string) => void, onError: (m: string) => void): Promise<Listening> {
  const mode = await detectSpeech();
  if (mode === 'browser') {
    const R = Recognition();
    const rec = new R();
    rec.lang = 'zh-CN';
    rec.interimResults = false;
    rec.continuous = true;
    let text = '';
    rec.onresult = (e: any) => {
      for (let i = e.resultIndex; i < e.results.length; i++) if (e.results[i].isFinal) text += e.results[i][0].transcript;
    };
    rec.onerror = (e: any) => onError(e?.error === 'not-allowed' ? '没有麦克风权限' : '没听清，请再说一次');
    rec.onend = () => text && onText(text.trim());
    rec.start();
    return { stop: () => rec.stop() };
  }
  if (mode === 'server') {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const chunks: Blob[] = [];
    const mr = new MediaRecorder(stream);
    mr.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    mr.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop());
      const fd = new FormData();
      fd.append('audio', new Blob(chunks, { type: mr.mimeType || 'audio/webm' }), 'speech.webm');
      try {
        const r: any = await request({
          url: '/api/ui/speech',
          method: 'post',
          data: fd,
          headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }
        });
        if (r?.data?.text) onText(String(r.data.text).trim());
      } catch (e: any) {
        onError(e?.message ?? '语音识别失败');
      }
    };
    mr.start();
    return { stop: () => mr.state !== 'inactive' && mr.stop() };
  }
  onError('当前设备不支持语音输入');
  return { stop: () => undefined };
}

/** 测试用 */
export function resetSpeech() {
  speechMode.value = null;
  probing = null;
}

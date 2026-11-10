/**
 * 模拟摄像头：生成一段画面中央是二维码的 Y4M 视频，供 Chromium --use-file-for-fake-video-capture 使用。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { BarcodeFormat, EncodeHintType, QRCodeWriter } from '@zxing/library';

export function qrVideo(text: string, file = path.join(os.tmpdir(), `ls-scan-${process.pid}-${Date.now()}.y4m`)) {
  const W = 640;
  const H = 480;
  const size = 360;
  const hints = new Map<EncodeHintType, unknown>([[EncodeHintType.MARGIN, 2]]);
  const m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints as any);
  const y = Buffer.alloc(W * H, 235);
  const ox = (W - size) / 2;
  const oy = (H - size) / 2;
  for (let j = 0; j < size; j++) for (let i = 0; i < size; i++) if (m.get(i, j)) y[(oy + j) * W + ox + i] = 16;
  const uv = Buffer.alloc((W / 2) * (H / 2), 128);
  const frame = Buffer.concat([Buffer.from('FRAME\n'), y, uv, uv]);
  const frames = Array.from({ length: 10 }, () => frame);
  fs.writeFileSync(file, Buffer.concat([Buffer.from(`YUV4MPEG2 W${W} H${H} F10:1 Ip A1:1 C420jpeg\n`), ...frames]));
  return file;
}

/** 拍照压缩：长边不超过 maxSide 像素，转 JPEG（质量 0.8）；压缩后反而更大时保留原图 */
export async function compressImage(file: Blob, maxSide = 1600, quality = 0.8): Promise<Blob> {
  const bmp = await loadImage(file);
  const scale = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * scale));
  const h = Math.max(1, Math.round(bmp.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, w, h);
  const out = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/jpeg', quality));
  return out && (out.size < file.size || scale < 1) ? out : file;
}

export function loadImage(file: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('无法读取图片'));
    };
    img.src = url;
  });
}

export const jpgName = (name: string) => name.replace(/\.[^.]+$/, '') + '.jpg';

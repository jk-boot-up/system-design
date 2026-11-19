/** Image URLs: catalog returns full URLs; search returns an object key under the image base URL. */
export function imageUrl(base: string, keyOrUrl: string | null | undefined): string | undefined {
  if (!keyOrUrl) return undefined;
  if (/^https?:\/\//.test(keyOrUrl)) return keyOrUrl;
  return `${base.replace(/\/$/, '')}/${keyOrUrl.split('/').map(encodeURIComponent).join('/')}`;
}

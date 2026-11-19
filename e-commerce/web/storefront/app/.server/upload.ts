import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { runtime } from './runtime.ts';

/**
 * PUTs an image to a presigned object-storage URL from the server. The URL's host is part of the signature, so when the
 * public host is not reachable from inside (local stack), the request goes to OBJECT_STORAGE_INTERNAL_ORIGIN with the
 * signed Host header unchanged. Only URLs on the configured image host are accepted (no request forgery to other hosts).
 */
export async function putPresigned(url: string, contentType: string, body: Uint8Array): Promise<boolean> {
  const cfg = runtime().config;
  const target = new URL(url);
  if (target.origin !== new URL(cfg.IMAGE_BASE_URL).origin) throw new Error('upload URL is not on the image host');
  const via = cfg.OBJECT_STORAGE_INTERNAL_ORIGIN ? new URL(cfg.OBJECT_STORAGE_INTERNAL_ORIGIN) : target;
  const send = via.protocol === 'https:' ? httpsRequest : httpRequest;
  return new Promise((resolve, reject) => {
    const req = send({
      protocol: via.protocol, hostname: via.hostname, port: via.port, method: 'PUT', path: target.pathname + target.search,
      headers: { Host: target.host, 'Content-Type': contentType, 'Content-Length': body.byteLength },
      timeout: 20_000,
    }, (res) => {
      res.resume();
      resolve((res.statusCode ?? 500) < 300);
    });
    req.on('timeout', () => req.destroy(new Error('upload timed out')));
    req.on('error', reject);
    req.end(body);
  });
}

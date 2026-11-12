import type { Env } from './index.ts';

const b64url = (b: Uint8Array): string => btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const enc = (o: unknown): string => b64url(new TextEncoder().encode(JSON.stringify(o)));

export async function appToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${enc({ alg: 'RS256', typ: 'JWT' })}.${enc({ iat: now - 60, exp: now + 540, iss: env.GH_APP_ID })}`;
  const der = Uint8Array.from(atob(env.GH_APP_KEY.replace(/-----[^-]+-----|\s/g, '')), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const res = await gh(`${unsigned}.${b64url(sig)}`, 'POST', `/app/installations/${env.GH_INSTALLATION_ID}/access_tokens`);
  if (!res.ok) throw new Error(`installation token: ${res.status}`);
  return ((await res.json()) as { token: string }).token;
}

export const gh = (token: string, method: string, path: string, body?: unknown): Promise<Response> =>
  fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'User-Agent': 'crbyob-reports',
      'X-GitHub-Api-Version': '2022-11-28',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

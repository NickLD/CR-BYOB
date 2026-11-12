import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.ts';
import { footer, replyText, tagOf } from '../src/rules.ts';

const ID = '0123456789abcdef0123456789abcdef';
const TAG = '3eb1bd439947eb762998e566ccc2e099';
const REPO = 'NickLD/CR-BYOB';
const API = 'https://api.github.com';

const keys = await crypto.subtle.generateKey(
  { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
  true,
  ['sign', 'verify'],
);
const der = Buffer.from(await crypto.subtle.exportKey('pkcs8', keys.privateKey)).toString('base64');
const pem = `-----BEGIN PRIVATE KEY-----\n${der.match(/.{1,64}/g)!.join('\n')}\n-----END PRIVATE KEY-----\n`;

type Call = { method: string; url: string; headers: Headers; body: any };
let calls: Call[] = [];
let issue: any;
let createdLabels: string[];
let labelsThrow: boolean;

globalThis.fetch = (async (input: any, init: any = {}) => {
  const url = String(input);
  const method = init.method ?? 'GET';
  calls.push({ method, url, headers: new Headers(init.headers), body: init.body ? JSON.parse(init.body) : undefined });
  const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status });
  if (method === 'POST' && url === `${API}/app/installations/77/access_tokens`) return json({ token: 'inst-tok' }, 201);
  if (method === 'GET' && url === `${API}/repos/${REPO}/issues/42`) return json(issue);
  if (method === 'POST' && url === `${API}/repos/${REPO}/issues/42/comments`) return json({}, 201);
  if (method === 'POST' && url === `${API}/repos/${REPO}/issues`)
    return json({ number: 5, html_url: `https://github.com/${REPO}/issues/5`, labels: createdLabels.map((name) => ({ name })) }, 201);
  if (method === 'POST' && url === `${API}/repos/${REPO}/issues/5/labels`) {
    if (labelsThrow) throw new TypeError('network down');
    return json([]);
  }
  return json({ message: 'Not Found' }, 404);
}) as typeof fetch;

let allow = true;
const env = {
  GH_APP_ID: '123',
  GH_INSTALLATION_ID: '77',
  GH_APP_KEY: pem,
  GH_REPO: REPO,
  GH_BOT_LOGIN: 'crbyob-reports[bot]',
  LIMITER: { limit: async (_: { key: string }) => ({ success: allow }) },
};

function reset() {
  calls = [];
  allow = true;
  createdLabels = ['device report', 'anonymous'];
  labelsThrow = false;
  issue = { number: 42, body: `### Scanner\n\nOtter${footer(TAG)}`, labels: [{ name: 'device report' }, { name: 'anonymous' }], user: { login: 'crbyob-reports[bot]' } };
}

const post = (path: string, body: unknown, type = 'application/json') =>
  worker.fetch(new Request(`https://w.example${path}`, { method: 'POST', headers: { 'Content-Type': type }, body: typeof body === 'string' ? body : JSON.stringify(body) }), env);
const commented = () => calls.some((c) => c.method === 'POST' && c.url.endsWith('/comments'));

test('/reply to an issue with another tag is 403 and posts nothing', async () => {
  reset();
  issue.body = `### Scanner${footer('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')}`;
  const res = await post('/reply', { id: ID, issue: 42, body: 'hi' });
  assert.equal(res.status, 403);
  assert.equal(typeof (await res.json()).error, 'string');
  assert.equal(commented(), false);
});

test('/reply to an issue without the anonymous label is 403 and posts nothing', async () => {
  reset();
  issue.labels = [{ name: 'device report' }];
  const res = await post('/reply', { id: ID, issue: 42, body: 'hi' });
  assert.equal(res.status, 403);
  assert.equal(commented(), false);
});

test('/reply to an anonymous issue with the tag but not opened by the App is 403 and posts nothing', async () => {
  reset();
  issue.user = { login: 'stranger' };
  const res = await post('/reply', { id: ID, issue: 42, body: 'hi' });
  assert.equal(res.status, 403);
  assert.equal(commented(), false);
});

test('/reply with matching tag and label posts the defanged reply', async () => {
  reset();
  const text = 'thanks @NickLD';
  const res = await post('/reply', { id: ID, issue: 42, body: text });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
  const c = calls.find((c) => c.method === 'POST' && c.url === `${API}/repos/${REPO}/issues/42/comments`)!;
  assert.equal(c.body.body, replyText(TAG, text));
  assert.ok(c.body.body.includes('@‍NickLD'));
  assert.equal(c.headers.get('Authorization'), 'Bearer inst-tok');
});

test('/report files the issue with footer and labels, signed as the App', async () => {
  reset();
  const res = await post('/report', { id: ID, title: '[Device] Otter @x', body: '### Scanner\n\nhi @NickLD' });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { number: 5, url: `https://github.com/${REPO}/issues/5`, bot: 'crbyob-reports[bot]' });

  const create = calls.find((c) => c.method === 'POST' && c.url === `${API}/repos/${REPO}/issues`)!;
  assert.equal(create.body.title, '[Device] Otter @‍x');
  assert.ok(create.body.body.endsWith(footer(await tagOf(ID))));
  assert.ok(create.body.body.startsWith('### Scanner\n\nhi @‍NickLD'));
  assert.deepEqual([...create.body.labels].sort(), ['anonymous', 'device report']);
  assert.equal(create.headers.get('Accept'), 'application/vnd.github+json');
  assert.equal(create.headers.get('User-Agent'), 'crbyob-reports');
  assert.equal(create.headers.get('X-GitHub-Api-Version'), '2022-11-28');
  assert.equal(create.headers.get('Content-Type'), 'application/json');
  assert.equal(calls.some((c) => c.url.endsWith('/issues/5/labels')), false);

  const tok = calls.find((c) => c.url.endsWith('/access_tokens'))!;
  const [h, p, s] = tok.headers.get('Authorization')!.replace('Bearer ', '').split('.');
  const dec = (x: string) => JSON.parse(Buffer.from(x, 'base64url').toString());
  assert.deepEqual(dec(h), { alg: 'RS256', typ: 'JWT' });
  const claims = dec(p);
  assert.equal(claims.iss, '123');
  assert.equal(claims.exp - claims.iat, 600);
  assert.ok(Math.abs(claims.iat - (Date.now() / 1000 - 60)) < 5);
  assert.ok(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', keys.publicKey, Buffer.from(s, 'base64url'), new TextEncoder().encode(`${h}.${p}`)));
});

test('/report adds labels GitHub dropped on create', async () => {
  reset();
  createdLabels = [];
  const res = await post('/report', { id: ID, title: '[Device] Otter', body: '### Scanner' });
  assert.equal(res.status, 200);
  const add = calls.find((c) => c.method === 'POST' && c.url === `${API}/repos/${REPO}/issues/5/labels`)!;
  assert.deepEqual([...add.body.labels].sort(), ['anonymous', 'device report']);
});

test('/report still answers 200 when the labels follow-up throws', async () => {
  reset();
  createdLabels = [];
  labelsThrow = true;
  const res = await post('/report', { id: ID, title: '[Device] Otter', body: '### Scanner' });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).number, 5);
});

test('rate limit, unknown route, bad input', async () => {
  reset();
  allow = false;
  assert.equal((await post('/report', { id: ID, title: '[Device] x', body: '### Scanner' })).status, 429);
  allow = true;
  const get = await worker.fetch(new Request('https://w.example/report'), env);
  assert.equal(get.status, 404);
  assert.equal(typeof (await get.json()).error, 'string');
  assert.equal((await post('/report', '{nope')).status, 400);
  assert.equal((await post('/report', { id: 'nope', title: '[Device] x', body: '### Scanner' })).status, 400);
  assert.equal((await post('/reply', { id: ID, issue: 0, body: 'x' })).status, 400);
  assert.equal(calls.length, 0);
});

test('a body that is not application/json is 415 before anything else', async () => {
  reset();
  const res = await post('/report', JSON.stringify({ id: ID, title: '[Device] x', body: '### Scanner' }), 'text/plain');
  assert.equal(res.status, 415);
  assert.equal(typeof (await res.json()).error, 'string');
  assert.equal(calls.length, 0);
  assert.equal((await post('/report', { id: ID, title: '[Device] x', body: '### Scanner' }, 'application/json; charset=utf-8')).status, 200);
});

test('GitHub failure is 502', async () => {
  reset();
  const res = await post('/reply', { id: ID, issue: 9, body: 'hi' });
  assert.equal(res.status, 502);
  assert.equal(commented(), false);
});

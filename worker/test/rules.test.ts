import test from 'node:test';
import assert from 'node:assert/strict';
import { tagOf, validId, parseReport, parseReply, defang, footer, replyText, hasTag } from '../src/rules.ts';

const ID = '0123456789abcdef0123456789abcdef';
const body = '### Scanner\n\nOtter';

test('tagOf matches the shared vector', async () => {
  assert.equal(await tagOf(ID), '3eb1bd439947');
});

test('validId', () => {
  assert.equal(validId(ID), true);
  assert.equal(validId(ID.slice(1)), false);
  assert.equal(validId(ID.toUpperCase()), false);
  assert.equal(validId(123), false);
});

test('parseReport', () => {
  assert.deepEqual(parseReport({ id: ID, title: '[Device] Otter on Pixel', body }), { title: '[Device] Otter on Pixel', body });
  assert.deepEqual(parseReport({ id: ID, title: 'Otter on Pixel', body }), { title: '[Device] Otter on Pixel', body });
  assert.equal(typeof parseReport({ title: 'x', body: 'nope' }), 'string');
  assert.equal(typeof parseReport({ title: 'x', body: '### Scanner' + 'a'.repeat(60_000 - 11) }), 'object');
  assert.equal(typeof parseReport({ title: 'x', body: '### Scanner' + 'a'.repeat(60_000 - 10) }), 'string');
  assert.equal(typeof parseReport({ title: 'a'.repeat(257), body }), 'string');
  assert.equal(typeof parseReport({ title: '', body }), 'string');
  assert.equal(typeof parseReport(null), 'string');
});

test('parseReply', () => {
  for (const issue of [0, -1, 1.5, '3']) assert.equal(typeof parseReply({ issue, body: 'x' }), 'string');
  assert.equal(typeof parseReply({ issue: 3, body: '' }), 'string');
  assert.equal(typeof parseReply({ issue: 3, body: 'a'.repeat(10_001) }), 'string');
  assert.deepEqual(parseReply({ issue: 3, body: 'hi' }), { issue: 3, body: 'hi' });
});

test('defang', () => {
  assert.equal(defang('hi @NickLD'), 'hi @‍NickLD');
});

test('hasTag', () => {
  const b = body + footer('3eb1bd439947');
  assert.equal(hasTag(b, '3eb1bd439947'), true);
  assert.equal(hasTag(b, '000000000000'), false);
});

test('replyText', () => {
  assert.ok(replyText('abc', 'x').startsWith('**Reply from the reporter** (tag `abc`)\n\n'));
});

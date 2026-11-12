export async function tagOf(id: string): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(id));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 32);
}

export const validId = (id: unknown): id is string => typeof id === 'string' && /^[0-9a-f]{32}$/.test(id);

export function parseReport(j: any): { title: string; body: string } | string {
  if (typeof j?.title !== 'string' || typeof j?.body !== 'string') return 'title and body are required';
  let title = j.title.trim();
  if (!title) return 'title is empty';
  // Prefix rather than reject: a 400 would lose the report over a title edit.
  if (!title.startsWith('[Device]')) title = '[Device] ' + title;
  if (title.length > 256) return 'title is too long';
  if (!j.body.startsWith('### Scanner')) return 'body must start with "### Scanner"';
  if (j.body.length > 60_000) return 'body is too long';
  return { title, body: j.body };
}

export function parseReply(j: any): { issue: number; body: string } | string {
  if (!Number.isInteger(j?.issue) || j.issue < 1) return 'issue must be a positive integer';
  if (typeof j.body !== 'string' || j.body.length < 1 || j.body.length > 10_000) return 'body must be 1-10000 characters';
  return { issue: j.issue, body: j.body };
}

export const defang = (s: string): string => s.replaceAll('@', '@‍');

export const footer = (tag: string): string =>
  `\n\n---\nSent anonymously from the CR-BYOB app.\nReporter tag: \`${tag}\``;

export const replyText = (tag: string, body: string): string =>
  `**Reply from the reporter** (tag \`${tag}\`)\n\n${defang(body)}`;

// Only the last line counts: it is the footer the Worker appends; the body above it is the reporter's text.
export const hasTag = (issueBody: string, tag: string): boolean =>
  issueBody.trimEnd().endsWith(`\nReporter tag: \`${tag}\``);

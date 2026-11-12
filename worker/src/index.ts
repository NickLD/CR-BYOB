import { appToken, gh } from './github.ts';
import { defang, footer, hasTag, parseReply, parseReport, replyText, tagOf, validId } from './rules.ts';

export interface Env {
  GH_APP_ID: string;
  GH_INSTALLATION_ID: string;
  GH_APP_KEY: string;
  GH_REPO: string;
  GH_BOT_LOGIN: string;
  LIMITER: { limit(o: { key: string }): Promise<{ success: boolean }> };
}

const LABELS = ['device report', 'anonymous'];
const json = (o: unknown, status = 200): Response =>
  new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json' } });
const fail = (status: number, error: string): Response => json({ error }, status);

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const path = new URL(req.url).pathname;
    if (req.method !== 'POST' || (path !== '/report' && path !== '/reply')) return fail(404, 'not found');
    if (!(await env.LIMITER.limit({ key: req.headers.get('CF-Connecting-IP') ?? 'none' })).success)
      return fail(429, 'too many requests, try again in a minute');

    let j: any;
    try {
      j = await req.json();
    } catch {
      return fail(400, 'invalid JSON');
    }
    if (!validId(j?.id)) return fail(400, 'id must be 32 lowercase hex characters');
    const parsed = path === '/report' ? parseReport(j) : parseReply(j);
    if (typeof parsed === 'string') return fail(400, parsed);
    const tag = await tagOf(j.id);

    try {
      const token = await appToken(env);
      const repo = `/repos/${env.GH_REPO}/issues`;
      if ('title' in parsed) {
        const res = await gh(token, 'POST', repo, { title: defang(parsed.title), body: defang(parsed.body) + footer(tag), labels: LABELS });
        if (!res.ok) return fail(502, `GitHub answered ${res.status}`);
        const issue: any = await res.json();
        const got = (issue.labels ?? []).map((l: any) => l.name);
        // GitHub can silently drop labels on create; the issue exists either way, so a failure here is not retried.
        if (!LABELS.every((l) => got.includes(l))) await gh(token, 'POST', `${repo}/${issue.number}/labels`, { labels: LABELS });
        return json({ number: issue.number, url: issue.html_url, bot: env.GH_BOT_LOGIN });
      }

      const res = await gh(token, 'GET', `${repo}/${parsed.issue}`);
      if (!res.ok) return fail(502, `GitHub answered ${res.status}`);
      const issue: any = await res.json();
      if (!issue.labels?.some((l: any) => l.name === 'anonymous') || !hasTag(issue.body ?? '', tag))
        return fail(403, 'not your report');
      // replyText defangs the text itself.
      const posted = await gh(token, 'POST', `${repo}/${parsed.issue}/comments`, { body: replyText(tag, parsed.body) });
      if (!posted.ok) return fail(502, `GitHub answered ${posted.status}`);
      return json({ ok: true });
    } catch {
      return fail(502, 'GitHub request failed');
    }
  },
};

import { proxyToUpstream, wantsLowReasoning, withLowReasoning } from '@/lib/ai-proxy';
import { authed, HttpError } from '@/lib/http';

// The server's language model upstream (#93): a chat completion, streamed when asked.
// A request marked for little reasoning (#136) gets the server's way of asking its
// model for that; any other passes through untouched.
export async function POST(req: Request) {
  return authed(req, 'llm', async () => {
    if (!wantsLowReasoning(req)) return proxyToUpstream(req, 'llm', '/chat/completions');
    const body: unknown = await req.json().catch(() => null);
    if (body === null || typeof body !== 'object' || Array.isArray(body)) throw new HttpError(400, 'invalid_body');
    return proxyToUpstream(req, 'llm', '/chat/completions', JSON.stringify(withLowReasoning(body as Record<string, unknown>)));
  });
}

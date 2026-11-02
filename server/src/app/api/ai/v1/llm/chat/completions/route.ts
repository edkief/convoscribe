import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's language model upstream (#93): a chat completion, streamed when asked.
export async function POST(req: Request) {
  return authed(req, () => proxyToUpstream(req, 'llm', '/chat/completions'));
}

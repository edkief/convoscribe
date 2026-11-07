import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's decision service upstream (#101): laya decisions about up to 64 texts at once.
export async function POST(req: Request) {
  return authed(req, () => proxyToUpstream(req, 'decision', '/v1/systemone/batch'));
}

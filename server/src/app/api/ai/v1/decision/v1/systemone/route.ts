import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's decision service upstream (#101): one laya decision.
export async function POST(req: Request) {
  return authed(req, () => proxyToUpstream(req, 'decision', '/v1/systemone'));
}

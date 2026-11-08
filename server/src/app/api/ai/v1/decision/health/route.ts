import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's decision service upstream (#101): whether it is up.
export async function GET(req: Request) {
  return authed(req, 'decision', () => proxyToUpstream(req, 'decision', '/health'));
}

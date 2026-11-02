import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's transcription upstream (#93): the models it offers.
export async function GET(req: Request) {
  return authed(req, () => proxyToUpstream(req, 'asr', '/models'));
}

import { proxyToUpstream } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

// The server's transcription upstream (#93): a recording, streamed through without being held in memory.
export async function POST(req: Request) {
  return authed(req, 'asr', () => proxyToUpstream(req, 'asr', '/audio/transcriptions'));
}

import { upstreamOf } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

/**
 * What this server offers a connected device (#93): its language model,
 * transcription and decision service (#101), each `{ model }` (null when
 * the operator names none) or null when not offered. Devices reach them at the fixed /api/ai/v1 paths
 * on this server; no upstream address or key is ever sent. An older server
 * answers 404 here, meaning it offers nothing.
 */
export async function GET(req: Request) {
  return authed(req, async () => {
    const offer = (service: 'llm' | 'asr' | 'decision') => {
      const upstream = upstreamOf(service);
      return upstream ? { model: upstream.model ?? null } : null;
    };
    return Response.json({ llm: offer('llm'), transcription: offer('asr'), decision: offer('decision') }, { headers: { 'cache-control': 'no-store' } });
  });
}

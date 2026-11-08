import { servicesOf } from '@/lib/access';
import { upstreamOf } from '@/lib/ai-proxy';
import { authed } from '@/lib/http';

/**
 * What this server offers a connected device (#93): its language model,
 * transcription and decision service (#101), each `{ model }` (null when
 * the operator names none) or null when not offered. Devices reach them at the fixed /api/ai/v1 paths
 * on this server; no upstream address or key is ever sent. An older server
 * answers 404 here, meaning it offers nothing. A service the user was not
 * given (#145) is not offered to them; every user may ask, sync or not.
 */
export async function GET(req: Request) {
  return authed(req, null, async (owner) => {
    const services = await servicesOf(owner);
    const offer = (service: 'llm' | 'asr' | 'decision') => {
      const upstream = services.has(service) ? upstreamOf(service) : null;
      return upstream ? { model: upstream.model ?? null } : null;
    };
    return Response.json({ llm: offer('llm'), transcription: offer('asr'), decision: offer('decision') }, { headers: { 'cache-control': 'no-store' } });
  });
}

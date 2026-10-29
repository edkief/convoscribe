// Liveness for the proxy and the cluster: no auth, no dependencies touched.
export function GET() {
  return Response.json({ status: 'ok' });
}

// Preloaded by the image (NODE_OPTIONS=--import) before Next's standalone
// server starts (#93). Next creates its server with Node's defaults, whose
// requestTimeout cuts off any request not fully received within 5 minutes:
// too short to upload a long recording to /api/ai/v1/asr over a slow link.
// As in the app, a connection may take as long as it needs while data
// moves, and is closed after 15 minutes with nothing sent either way.
// The ingress in front still applies its own limits.
import http from 'node:http';

const IDLE_MS = 15 * 60 * 1000;
const createServer = http.createServer;

http.createServer = function (options, listener) {
  if (typeof options === 'function') [options, listener] = [{}, options];
  const server = createServer.call(this, { requestTimeout: 0, ...options }, listener);
  server.setTimeout(IDLE_MS);
  return server;
};

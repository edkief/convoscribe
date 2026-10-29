# The sync server image. Build context is server/ (see .k8s-build.yaml):
#   docker build -t convoscribe-server server
# Configuration is environment variables only; see README.md.

ARG NODE_IMAGE=node:24-alpine

FROM ${NODE_IMAGE} AS build
# Keep in step with "packageManager" in package.json.
RUN npm install --global --silent pnpm@12.15.0
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN pnpm install --frozen-lockfile
COPY . .
# The same gate as scripts/server-gate.sh: no image from a failing tree.
RUN pnpm check && pnpm build

FROM ${NODE_IMAGE}
WORKDIR /app
ENV NODE_ENV=production \
    HOSTNAME=0.0.0.0 \
    PORT=3000
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
# Applied on startup by src/instrumentation.ts.
COPY --from=build --chown=node:node /app/drizzle ./drizzle
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "server.js"]

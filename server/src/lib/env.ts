import { z } from 'zod';

const blankToUndefined = (value: unknown) => (value === '' ? undefined : value);

// An http(s) base URL, kept without a trailing slash so paths append cleanly.
const httpUrl = z
  .url({ protocol: /^https?$/ })
  .transform((value) => value.replace(/\/+$/, ''));

const schema = z
  .object({
    DATABASE_URL: z.url(),
    S3_ENDPOINT: z.preprocess(blankToUndefined, z.url().optional()),
    S3_REGION: z.string().min(1).default('us-east-1'),
    S3_BUCKET: z.string().min(1),
    S3_ACCESS_KEY_ID: z.string().min(1),
    S3_SECRET_ACCESS_KEY: z.string().min(1),
    // MinIO and most self-hosted stores need path-style URLs.
    S3_FORCE_PATH_STYLE: z
      .enum(['true', 'false'])
      .default('true')
      .transform((value) => value === 'true'),
    AUTH_MODE: z.enum(['api-key', 'authz-proxy']),
    SYNC_API_KEY: z.preprocess(blankToUndefined, z.string().min(32).optional()),
    AUTHZ_PROXY_URL: z.preprocess(blankToUndefined, z.url().optional()),
    // authz-proxy roles that make a user an admin (#79); the SDK's default otherwise.
    ADMIN_ROLES: z.preprocess(
      blankToUndefined,
      z
        .string()
        .transform((value) => value.split(',').map((role) => role.trim()).filter(Boolean))
        .optional(),
    ),
    // Where the web app's Sign out goes in authz-proxy mode, whose session is the proxy's (#80).
    WEB_SIGN_OUT_URL: z.preprocess(blankToUndefined, z.url().optional()),
    // The daily storage clean-up (#81): `dry-run` records what it would remove and removes nothing.
    CLEANUP: z.preprocess(blankToUndefined, z.enum(['on', 'dry-run', 'off']).default('on')),
    // Upstreams the server offers its users through /api/ai/v1 (#93): an
    // OpenAI-compatible base URL each, with an optional key and model.
    LLM_UPSTREAM_URL: z.preprocess(blankToUndefined, httpUrl.optional()),
    LLM_UPSTREAM_KEY: z.preprocess(blankToUndefined, z.string().optional()),
    LLM_MODEL: z.preprocess(blankToUndefined, z.string().optional()),
    ASR_UPSTREAM_URL: z.preprocess(blankToUndefined, httpUrl.optional()),
    ASR_UPSTREAM_KEY: z.preprocess(blankToUndefined, z.string().optional()),
    ASR_MODEL: z.preprocess(blankToUndefined, z.string().optional()),
  })
  .superRefine((env, ctx) => {
    if (env.AUTH_MODE === 'api-key' && !env.SYNC_API_KEY) {
      ctx.addIssue({ code: 'custom', path: ['SYNC_API_KEY'], message: 'required when AUTH_MODE=api-key' });
    }
    if (env.AUTH_MODE === 'authz-proxy' && !env.AUTHZ_PROXY_URL) {
      ctx.addIssue({ code: 'custom', path: ['AUTHZ_PROXY_URL'], message: 'required when AUTH_MODE=authz-proxy' });
    }
    for (const service of ['LLM', 'ASR'] as const) {
      if (env[`${service}_UPSTREAM_URL`]) continue;
      for (const name of [`${service}_UPSTREAM_KEY`, `${service}_MODEL`] as const) {
        if (env[name] !== undefined) {
          ctx.addIssue({ code: 'custom', path: [name], message: `needs ${service}_UPSTREAM_URL` });
        }
      }
    }
  });

export type Env = z.infer<typeof schema>;

/** Parse and validate the server configuration; throws listing every bad variable. */
export function loadEnv(source: Record<string, string | undefined> = process.env): Env {
  const result = schema.safeParse(source);
  if (!result.success) {
    throw new Error(`Invalid server configuration:\n${z.prettifyError(result.error)}`);
  }
  return result.data;
}

let cached: Env | undefined;

/** The validated configuration, parsed on first use. */
export function env(): Env {
  cached ??= loadEnv();
  return cached;
}

/** Forget the cached configuration (tests only). */
export function resetEnv() {
  cached = undefined;
}

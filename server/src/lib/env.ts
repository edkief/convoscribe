import { z } from 'zod';

const blankToUndefined = (value: unknown) => (value === '' ? undefined : value);

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
  })
  .superRefine((env, ctx) => {
    if (env.AUTH_MODE === 'api-key' && !env.SYNC_API_KEY) {
      ctx.addIssue({ code: 'custom', path: ['SYNC_API_KEY'], message: 'required when AUTH_MODE=api-key' });
    }
    if (env.AUTH_MODE === 'authz-proxy' && !env.AUTHZ_PROXY_URL) {
      ctx.addIssue({ code: 'custom', path: ['AUTHZ_PROXY_URL'], message: 'required when AUTH_MODE=authz-proxy' });
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

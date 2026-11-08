import { z } from 'zod';

const blankToUndefined = (value: unknown) => (value === '' ? undefined : value);

// An http(s) base URL, kept without a trailing slash so paths append cleanly.
const httpUrl = z
  .url({ protocol: /^https?$/ })
  .transform((value) => value.replace(/\/+$/, ''));

// A JSON object, written as JSON text: fields the server adds to some requests.
const jsonObject = z.string().transform((text, ctx) => {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    ctx.addIssue({ code: 'custom', message: 'not valid JSON' });
    return z.NEVER;
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    ctx.addIssue({ code: 'custom', message: 'must be a JSON object, e.g. {"reasoning_effort":"low"}' });
    return z.NEVER;
  }
  return value as Record<string, unknown>;
});

/**
 * What a user may be given (#145): syncing sessions and library items, and
 * each upstream the server offers. The ids are those of the /api/ai/v1 paths.
 */
export const ACCESS_SERVICES = ['sync', 'llm', 'asr', 'decision'] as const;
export type AccessService = (typeof ACCESS_SERVICES)[number];

// A comma-separated list of services, or `none`.
const serviceList = z.string().transform((text, ctx) => {
  const names = text.split(',').map((name) => name.trim().toLowerCase()).filter(Boolean);
  if (names.length === 1 && names[0] === 'none') return [] as AccessService[];
  const unknown = names.filter((name) => !(ACCESS_SERVICES as readonly string[]).includes(name));
  if (unknown.length > 0 || names.length === 0) {
    ctx.addIssue({ code: 'custom', message: `a comma-separated list of ${ACCESS_SERVICES.join(', ')}, or none` });
    return z.NEVER;
  }
  return ACCESS_SERVICES.filter((service) => names.includes(service));
});

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
    // How much transcript the web app's summaries send in one call (#117), the
    // app's *Max transcript characters*; a longer one is summarised in parts.
    LLM_MAX_TRANSCRIPT_CHARS: z.preprocess(blankToUndefined, z.coerce.number().int().min(1000).default(24000)),
    // How the language model is asked for little reasoning (#136), for talking-point
    // checks: fields merged into those requests, since every model has its own switch,
    // e.g. {"chat_template_kwargs":{"enable_thinking":false}}. Unset leaves them as sent.
    LLM_LOW_REASONING: z.preprocess(blankToUndefined, jsonObject.optional()),
    ASR_UPSTREAM_URL: z.preprocess(blankToUndefined, httpUrl.optional()),
    ASR_UPSTREAM_KEY: z.preprocess(blankToUndefined, z.string().optional()),
    ASR_MODEL: z.preprocess(blankToUndefined, z.string().optional()),
    // A laya decision service (#101), for talking-point checks: its base URL
    // (where /v1/systemone lives), an optional key and an optional model
    // (english, multilingual; none lets laya pick by language).
    DECISION_UPSTREAM_URL: z.preprocess(blankToUndefined, httpUrl.optional()),
    DECISION_UPSTREAM_KEY: z.preprocess(blankToUndefined, z.string().optional()),
    DECISION_MODEL: z.preprocess(blankToUndefined, z.string().optional()),
    // Whether the web app's talking-point check uses the decision service rather than the
    // language model (#136). Off: laya ticked points before they came up on real recordings.
    DECISION_FOR_TALKING_POINTS: z.preprocess(blankToUndefined, z.enum(['on', 'off']).default('off')),
    // The services of a user an admin has not given any (#145); every service when unset.
    DEFAULT_USER_SERVICES: z.preprocess(blankToUndefined, serviceList.default([...ACCESS_SERVICES])),
  })
  .superRefine((env, ctx) => {
    if (env.AUTH_MODE === 'api-key' && !env.SYNC_API_KEY) {
      ctx.addIssue({ code: 'custom', path: ['SYNC_API_KEY'], message: 'required when AUTH_MODE=api-key' });
    }
    if (env.AUTH_MODE === 'authz-proxy' && !env.AUTHZ_PROXY_URL) {
      ctx.addIssue({ code: 'custom', path: ['AUTHZ_PROXY_URL'], message: 'required when AUTH_MODE=authz-proxy' });
    }
    if (env.LLM_LOW_REASONING && !env.LLM_UPSTREAM_URL) {
      ctx.addIssue({ code: 'custom', path: ['LLM_LOW_REASONING'], message: 'needs LLM_UPSTREAM_URL' });
    }
    for (const service of ['LLM', 'ASR', 'DECISION'] as const) {
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

/**
 * What an admin calls each service a user may be given (#145), keyed as
 * `ACCESS_SERVICES` names them. Kept apart from `access.ts`, which reads
 * the database, so the browser can have it.
 */
export const SERVICE_LABELS = { sync: 'Sync', llm: 'Language model', asr: 'Transcription', decision: 'Decision service' } as const;

import type { PgDatabase, PgQueryResultHKT } from 'drizzle-orm/pg-core';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import path from 'node:path';
import { Pool } from 'pg';
import * as schema from '@/db/schema';
import { env } from './env';

/** The database, on node-postgres in the server and on PGlite in tests. */
export type Db = PgDatabase<PgQueryResultHKT, typeof schema>;

let database: Db | undefined;

export function db(): Db {
  database ??= drizzle(new Pool({ connectionString: env().DATABASE_URL }), { schema });
  return database;
}

/** Use `value` as the database (tests only); `undefined` goes back to DATABASE_URL. */
export function setDb(value: Db | undefined) {
  database = value;
}

/** Apply pending migrations from drizzle/ (shipped next to the server in the image). */
export async function migrateDb() {
  const pg = drizzle(new Pool({ connectionString: env().DATABASE_URL }), { schema });
  await migrate(pg, { migrationsFolder: path.join(process.cwd(), 'drizzle') });
  database ??= pg;
}

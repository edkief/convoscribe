import { PGlite } from '@electric-sql/pglite';
import { drizzle } from 'drizzle-orm/pglite';
import { migrate } from 'drizzle-orm/pglite/migrator';
import path from 'node:path';
import * as schema from '@/db/schema';
import { setDb, type Db } from '@/lib/db';

let client: PGlite | undefined;

/**
 * A fresh in-memory Postgres with every migration applied, installed as
 * `db()`. Each holds hundreds of MB until [closeTestDb] frees it.
 */
export async function createTestDb(): Promise<Db> {
  client = new PGlite();
  const database = drizzle(client, { schema });
  await migrate(database, { migrationsFolder: path.resolve(import.meta.dirname, '../../drizzle') });
  setDb(database as unknown as Db);
  return database as unknown as Db;
}

/** Close the Postgres [createTestDb] made last, and go back to no `db()`. */
export async function closeTestDb() {
  setDb(undefined);
  await client?.close();
  client = undefined;
}

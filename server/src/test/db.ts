import { PGlite } from '@electric-sql/pglite';
import { drizzle } from 'drizzle-orm/pglite';
import { migrate } from 'drizzle-orm/pglite/migrator';
import path from 'node:path';
import * as schema from '@/db/schema';
import { setDb, type Db } from '@/lib/db';

let client: PGlite | undefined;
let template: Promise<PGlite> | undefined;

/**
 * A Postgres with every migration applied, made once per test worker.
 * Booting PGlite and migrating takes seconds, so doing it for every test
 * made `beforeEach` hooks time out on a busy machine.
 */
function migratedTemplate(): Promise<PGlite> {
  template ??= (async () => {
    const pg = new PGlite();
    await migrate(drizzle(pg, { schema }), {
      migrationsFolder: path.resolve(import.meta.dirname, '../../drizzle'),
    });
    return pg;
  })();
  return template;
}

/**
 * A fresh in-memory Postgres with every migration applied, installed as
 * `db()`: a clone of the worker's migrated template, so each test starts
 * empty. Each holds hundreds of MB until [closeTestDb] frees it.
 */
export async function createTestDb(): Promise<Db> {
  client = (await (await migratedTemplate()).clone()) as PGlite;
  const database = drizzle(client, { schema });
  setDb(database as unknown as Db);
  return database as unknown as Db;
}

/** Close the Postgres [createTestDb] made last, and go back to no `db()`. */
export async function closeTestDb() {
  setDb(undefined);
  await client?.close();
  client = undefined;
}

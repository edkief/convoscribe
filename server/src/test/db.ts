import { PGlite } from '@electric-sql/pglite';
import { drizzle } from 'drizzle-orm/pglite';
import { migrate } from 'drizzle-orm/pglite/migrator';
import path from 'node:path';
import * as schema from '@/db/schema';
import { setDb, type Db } from '@/lib/db';

/** A fresh in-memory Postgres with every migration applied, installed as `db()`. */
export async function createTestDb(): Promise<Db> {
  const database = drizzle(new PGlite(), { schema });
  await migrate(database, { migrationsFolder: path.resolve(import.meta.dirname, '../../drizzle') });
  setDb(database as unknown as Db);
  return database as unknown as Db;
}

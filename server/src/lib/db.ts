import { drizzle, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import path from 'node:path';
import { Pool } from 'pg';
import * as schema from '@/db/schema';
import { env } from './env';

let database: NodePgDatabase<typeof schema> | undefined;

export function db(): NodePgDatabase<typeof schema> {
  database ??= drizzle(new Pool({ connectionString: env().DATABASE_URL }), { schema });
  return database;
}

/** Apply pending migrations from drizzle/ (shipped next to the server in the image). */
export async function migrateDb() {
  await migrate(db(), { migrationsFolder: path.join(process.cwd(), 'drizzle') });
}

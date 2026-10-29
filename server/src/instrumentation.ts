// Runs once when the server starts: fail fast on bad configuration, then
// bring the database schema up to date.
export async function register() {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return;
  try {
    const { env } = await import('./lib/env');
    env();
    const { migrateDb } = await import('./lib/db');
    await migrateDb();
  } catch (err) {
    // Next would log this and keep the process up, serving nothing.
    console.error(err);
    process.exit(1);
  }
}

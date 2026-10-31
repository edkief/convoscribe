// Runs once when the server starts: fail fast on bad configuration, bring
// the database schema up to date, then index sessions pushed before the web
// app existed (#80) in the background.
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
  const { backfillSessionIndex } = await import('./lib/backfill');
  backfillSessionIndex().then(
    ({ indexed, skipped }) => {
      if (indexed || skipped) console.log(`session index: indexed ${indexed} session(s), skipped ${skipped}`);
    },
    (err) => console.error('session index backfill failed', err),
  );
}

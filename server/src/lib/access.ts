import { eq } from 'drizzle-orm';
import { owners } from '@/db/schema';
import { db } from './db';
import { ACCESS_SERVICES, env, type AccessService } from './env';
import { HttpError } from './http';

export { ACCESS_SERVICES, type AccessService };

/** The services of a user an admin has not given any: `DEFAULT_USER_SERVICES` (#145). */
export function defaultServices(): AccessService[] {
  return env().DEFAULT_USER_SERVICES;
}

/** Only known services, once each, in their usual order: what a stored or sent list means. */
export function normaliseServices(services: readonly string[]): AccessService[] {
  return ACCESS_SERVICES.filter((service) => services.includes(service));
}

/**
 * What `owner` may use (#145): what an admin gave them, else the default.
 * Read on every request, so a change applies at once; someone this server
 * has not seen yet gets the default.
 */
export async function servicesOf(owner: string): Promise<ReadonlySet<AccessService>> {
  const [row] = await db().select({ services: owners.services }).from(owners).where(eq(owners.owner, owner));
  return new Set(row?.services ? normaliseServices(row.services) : defaultServices());
}

/** The 403 a user gets for a service they were not given. */
export const notAllowed = (service: AccessService) => new HttpError(403, 'service_not_allowed', { service });

/** Refuse, with `403 service_not_allowed`, a user who was not given `service`. */
export async function assertService(owner: string, service: AccessService): Promise<void> {
  if (!(await servicesOf(owner)).has(service)) throw notAllowed(service);
}

/** Give `owner` these services, or the default again when null. 404 for someone this server has not seen. */
export async function setServices(owner: string, services: readonly AccessService[] | null): Promise<void> {
  const [row] = await db()
    .update(owners)
    .set({ services: services && normaliseServices(services) })
    .where(eq(owners.owner, owner))
    .returning({ owner: owners.owner });
  if (!row) throw new HttpError(404, 'unknown_owner');
}

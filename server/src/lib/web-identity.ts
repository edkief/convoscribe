import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { servicesOf, type AccessService } from './access';
import { resolveIdentity, type Identity } from './auth';
import { preferredLocale, validZone } from './format';
import { noteActivity } from './owners';

/** The signed-in browser behind the page being rendered, or null (#80). */
export async function pageIdentity(): Promise<Identity | null> {
  return resolveIdentity({ headers: new Headers(await headers()), url: 'http://web/' });
}

/**
 * The signed-in browser behind the page being rendered; anyone else goes to
 * the sign-in page, which in authz-proxy mode explains where to sign in.
 */
export async function requireIdentity(): Promise<Identity> {
  const identity = await pageIdentity();
  if (!identity) redirect('/sign-in');
  await noteActivity(identity.owner);
  return identity;
}

/**
 * The signed-in admin behind the page being rendered (#82). Every admin page
 * calls this itself; to anyone else the page does not exist.
 */
export async function requireAdmin(): Promise<Identity> {
  const identity = await requireIdentity();
  if (!identity.isAdmin) notFound();
  return identity;
}

/**
 * The signed-in browser behind a library page, and what they may use
 * (#145). Someone not given `service` goes to the page that says so; the
 * library's pages need `sync`, since they show what was synced.
 */
export async function requireService(
  service: AccessService,
): Promise<Identity & { services: ReadonlySet<AccessService> }> {
  const identity = await requireIdentity();
  const services = await servicesOf(identity.owner);
  if (!services.has(service)) redirect('/no-access');
  return { ...identity, services };
}

/** The cookie the browser's IANA time zone is kept in, for dates and date filters. */
export const ZONE_COOKIE = 'tz';

/** The browser's time zone and locale, for formatting dates on the server. */
export async function pageLocale(): Promise<{ zone: string; locale: string; zoneKnown: boolean }> {
  const zone = (await cookies()).get(ZONE_COOKIE)?.value;
  return {
    zone: validZone(zone),
    locale: preferredLocale((await headers()).get('accept-language')),
    zoneKnown: zone !== undefined,
  };
}

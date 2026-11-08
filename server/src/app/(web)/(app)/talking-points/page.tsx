import type { Metadata } from 'next';
import { listTalkingPointLists } from '@/lib/talking-point-index';
import { requireService } from '@/lib/web-identity';
import { TalkingPointLists } from '../../_components/TalkingPointLists';

export const metadata: Metadata = { title: 'Talking points' };

/**
 * Talking-point lists (#104): what you mean to raise in a conversation,
 * written here before a meeting and attached on a device before Start.
 * Read from Postgres; every change reaches your devices at their next sync.
 */
export default async function TalkingPointsPage() {
  const { owner } = await requireService('sync');
  const lists = await listTalkingPointLists(owner);
  return (
    <div className="page library">
      <TalkingPointLists lists={lists} />
    </div>
  );
}

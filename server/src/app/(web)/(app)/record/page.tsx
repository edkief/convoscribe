import type { Metadata } from 'next';
import { requireService } from '@/lib/web-identity';
import { Recorder } from '../../_components/record/Recorder';

export const metadata: Metadata = { title: 'Record' };

/**
 * Record a session in the browser (#164): a live transcript made on this
 * computer by the app's speech model, and an upload that waits out network
 * blips. The recording becomes a session like one from a phone (#163).
 */
export default async function RecordPage() {
  await requireService('sync');
  return (
    <div className="page">
      <header className="page-head">
        <div>
          <p className="eyebrow">New session</p>
          <h1>Record</h1>
        </div>
      </header>
      <Recorder />
    </div>
  );
}

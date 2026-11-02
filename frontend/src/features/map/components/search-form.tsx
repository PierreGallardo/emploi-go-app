import { useId, useState } from 'react';
import { CircleAlertIcon } from 'lucide-react';
import type { Place, What, WhatPick } from '../schemas';
import { WhatField } from './what-field';
import { WhereField } from './where-field';

type Submission = What & { where: string };

/** « Quoi ? » and « Où ? »: Enter or a suggestion searches; there is no search button. */
export function SearchForm({
  scope,
  q,
  job,
  place,
  message,
  onSubmit,
  onPickWhat,
  onPickPlace,
  onClearPlace,
}: {
  scope: string;
  q: string;
  job: string | null;
  place: Place | null;
  /** Why the position is missing: an unknown place. */
  message: string | null;
  onSubmit: (submission: Submission) => void;
  onPickWhat: (what: WhatPick) => void;
  onPickPlace: (place: Place) => void;
  onClearPlace: () => void;
}) {
  const messageId = useId();
  const [draftQ, setDraftQ] = useState(job ?? q);
  // A job title picked in the field stays a job title until the text is edited.
  const [draftJob, setDraftJob] = useState(job);
  const [draftWhere, setDraftWhere] = useState(place?.label ?? '');
  // A search made elsewhere (a link followed, everything cleared) replaces what was typed.
  const [seen, setSeen] = useState({ q, job, label: place?.label ?? '' });
  if (seen.q !== q || seen.job !== job || seen.label !== (place?.label ?? '')) {
    setSeen({ q, job, label: place?.label ?? '' });
    setDraftQ(job ?? q);
    setDraftJob(job);
    setDraftWhere(place?.label ?? '');
  }
  return (
    <form
      role="search"
      aria-label="Rechercher un établissement"
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit({
          q: draftJob ? '' : draftQ.trim(),
          job: draftJob,
          where: draftWhere.trim(),
        });
      }}
    >
      <WhatField
        scope={scope}
        value={draftQ}
        onValueChange={(value) => {
          setDraftQ(value);
          setDraftJob(null);
        }}
        onPick={(what) => {
          setDraftQ(what.job ?? what.q);
          setDraftJob(what.job);
          onPickWhat(what);
        }}
        onClear={() => {
          setDraftJob(null);
          onPickWhat({ q: '', job: null });
        }}
      />
      <WhereField
        scope={scope}
        value={draftWhere}
        describedBy={message ? messageId : undefined}
        invalid={message !== null}
        onValueChange={setDraftWhere}
        onPick={(picked) => {
          setDraftWhere(picked.label);
          onPickPlace(picked);
        }}
        onClear={onClearPlace}
      />
      {/* Always there, so that a message appearing in it is announced. */}
      <p
        id={messageId}
        role="status"
        className="flex items-start gap-1.5 text-sm text-destructive empty:sr-only"
      >
        {message && (
          <>
            <CircleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            {message}
          </>
        )}
      </p>
      {/* Enter submits the form: with two fields, browsers expect a submit button for it, even a hidden one. */}
      <button type="submit" hidden />
    </form>
  );
}

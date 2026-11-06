# Conversation export: JSON format

*Export…* → **JSON** in a conversation's menu (issue #29) writes one
conversation as a JSON document for scripts. This page is its reference. A
machine-readable [JSON Schema](export-json.schema.json) describes the same
thing.

The writer is `app/src/main/java/com/convoscribe/export/JsonExport.kt`. Its
golden file, `app/src/test/resources/export/session.json`, is a complete
example.

## Versioning

- `format` is always `"convoscribe-transcript"`.
- `version` is `1`. It goes up when a field is removed, renamed or changes
  meaning. Adding a field does not change it, so readers should ignore
  fields they don't know.
- A field with no value is **left out** rather than written as `null`. For
  example, a line with no speaker has no `speakerIndex` or `speaker`, and a
  summary with no template has no `template`.
- A section the user did not tick in the dialog is **left out** entirely.
  Without *Transcript and marks* there is no `segments` or `marks`; without
  *Summary, action items and talking points* there is no `summary`,
  `actionItems` or `talkingPoints`; without *Questions and answers* there is
  no `questions`. A ticked section with nothing in it is an empty list, or
  for `summary`, absent.

Times on the recording's clock are in milliseconds from its start (`…Ms`).
Wall-clock times are ISO 8601 with the device's offset
(`2026-11-06T14:30:00+01:00`).

## Fields

### Top level

| Field | Type | |
|---|---|---|
| `format` | string | `"convoscribe-transcript"` |
| `version` | integer | `1` |
| `session` | object | Always present, see below |
| `speakers` | array | Always present; may be empty |
| `segments` | array | Transcript lines, in order of `startMs` |
| `marks` | array | Moments marked while recording (issue #30), in order of `atMs` |
| `summary` | object | The saved summary |
| `actionItems` | array | The summary's action items (issue #31), in list order; deleted ones are left out |
| `talkingPoints` | array | The talking points (issue #102), in list order |
| `questions` | array | Questions asked about the conversation, oldest first |

### `session`

| Field | Type | |
|---|---|---|
| `title` | string | As shown in the app |
| `createdAt` | string | When recording started, ISO 8601 |
| `createdAtMs` | integer | The same, in milliseconds since the Unix epoch |
| `durationMs` | integer | Length of the recording |
| `language` | string | Transcription language, `"en"` or `"fr"` |
| `participants` | array of strings | Known voices expected in the conversation; empty for anyone |
| `tags` | array of strings | The conversation's tags |

### `speakers[]`

| Field | Type | |
|---|---|---|
| `index` | integer | 0-based speaker number, what `segments[].speakerIndex` refers to |
| `label` | string | The name shown: `"Speaker 2"`, or the name it was given |

### `segments[]`

| Field | Type | |
|---|---|---|
| `startMs`, `endMs` | integer | Where the line is in the recording |
| `speakerIndex` | integer | Its speaker; absent when the line has none |
| `speaker` | string | That speaker's `label`, for convenience |
| `text` | string | The line as shown, including hand edits |
| `edited` | boolean | The text or speaker was changed by hand (issue #26) |
| `words` | array | The recogniser's words with their times; empty for lines recorded before word timings were kept |

`words[]` items have `text` (string), `startMs` and `endMs` (integers). A word
ends where the next one begins, and the last one ends at the line's end.
`words` comes from the recogniser, so after a hand edit it may no longer
match `text`.

### `marks[]`

| Field | Type | |
|---|---|---|
| `atMs` | integer | Where the mark is in the recording |
| `note` | string | The note typed with the mark |
| `fullNote` | string | The note written from the transcript around it (issue #48) |

### `summary`

| Field | Type | |
|---|---|---|
| `content` | string | The summary, in Markdown |
| `template` | string | Name of the template that wrote it (issue #27) |
| `createdAt` | string | When it was written, ISO 8601 |

### `actionItems[]`

| Field | Type | |
|---|---|---|
| `text` | string | What to do |
| `owner` | string | Who does it |
| `dueDate` | string | When, `yyyy-MM-dd` when the model gave a date |
| `atMs` | integer | Where in the recording it was agreed |
| `done` | boolean | Ticked off |

### `talkingPoints[]`

| Field | Type | |
|---|---|---|
| `text` | string | The point |
| `covered` | boolean | It came up (or was ticked by hand) |
| `coveredAtMs` | integer | Where in the recording it came up |

### `questions[]`

| Field | Type | |
|---|---|---|
| `question` | string | What was asked |
| `answer` | string | The answer, in Markdown; absent when none was saved |
| `askedAt` | string | When it was asked, ISO 8601 |

## Example

Shortened from the golden file:

```json
{
  "format": "convoscribe-transcript",
  "version": 1,
  "session": {
    "title": "Point d'équipe — budget",
    "createdAt": "2026-11-06T14:30:00+01:00",
    "createdAtMs": 1793971800000,
    "durationMs": 754000,
    "language": "fr",
    "participants": ["Élodie", "Marc"],
    "tags": ["équipe", "budget"]
  },
  "speakers": [{ "index": 0, "label": "Élodie" }, { "index": 1, "label": "Speaker 2" }],
  "segments": [
    {
      "startMs": 0,
      "endMs": 4200,
      "speakerIndex": 0,
      "speaker": "Élodie",
      "text": "Bonjour à tous, on commence par le budget.",
      "edited": false,
      "words": [{ "text": "Bonjour", "startMs": 0, "endMs": 525 }]
    },
    { "startMs": 17000, "endMs": 17000, "text": "Merci.", "edited": false, "words": [] }
  ],
  "marks": [{ "atMs": 5000, "note": "chiffres déplacements", "fullNote": "Les déplacements baissent de 10 %." }],
  "summary": { "content": "# Résumé\n\n- …", "template": "Réunion", "createdAt": "2026-11-06T14:50:00+01:00" },
  "actionItems": [{ "text": "Envoyer le compte rendu", "owner": "Élodie", "dueDate": "2026-11-13", "atMs": 19000, "done": false }],
  "talkingPoints": [{ "text": "Budget", "covered": true, "coveredAtMs": 1000 }],
  "questions": [{ "question": "Quel est le pourcentage ?", "answer": "Dix pour cent.", "askedAt": "2026-11-06T15:00:00+01:00" }]
}
```

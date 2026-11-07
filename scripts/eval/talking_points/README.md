# Talking-point check evaluation

`eval_talking_points.py` replays meetings the way ConvoScribe ticks talking points while
recording, and scores the checks. It answers questions like "does this model tick points live, and
how fast?" or "is laya good enough yet?" without recording anything. It needs only Python 3 and
network access to the model being tested.

The replay follows the app's `TalkingPointChecker` (issue #103):

- **When a line is available:** at its end, i.e. when the next line starts.
- **The loop:** every 15 s, the speech since the last check, plus 20 s before it, is checked against
  the points still open.
- **Timing:** checks run one at a time, so a slow check slows the loop down.
- **At the end:** one more check runs when the recording stops.

Each point gets one of these verdicts:

- **right:** ticked at or after the moment it was first discussed.
- **too early:** ticked before that moment. On a live checklist that's a false tick, even if the
  point comes up later.
- **false:** ticked when the meeting never covers it.
- **missed:** never ticked.

## Meetings

A meeting is a JSON file:

```json
{
  "name": "weekly-sync",
  "lines": [{"at": "00:05", "speaker": "Alice", "text": "Okay, let's get started."}],
  "points": [
    {"text": "Mobile release date and rollout plan", "covered": true, "from": "00:31"},
    {"text": "Q3 roadmap priorities", "covered": false, "from": null}
  ]
}
```

- **`from`:** the earliest line at which a tick is fair. Set it at the first line that covers part
  of the point, since a presenter wants partly covered points ticked.
- **Bundled meetings:** three short synthetic ones.
  - `weekly-sync.json`: a team sync.
  - `one-to-one.json`: a 1:1 with near-miss points, where words appear but the topic doesn't.
  - `planning-fr.json`: a planning call in French.
- **Real recordings:** keep them out of the repository. Export the transcript, label the points,
  and pass the file's path.

## Run

```sh
cd scripts/eval/talking_points

# A language model, live, as the app asks its own endpoint with low reasoning:
python3 eval_talking_points.py llm *.json --llm-url http://llama-chat.llama:8080/v1 \
  --extra-fields '{"chat_template_kwargs":{"reasoning_strength":"none"}}'

# Through the sync server, as a device using the server's model (key: LLM_KEY=<sync key>):
python3 eval_talking_points.py llm *.json --llm-url https://sync.example/api/ai/v1/llm \
  --header X-Convoscribe-Reasoning:low

# The whole transcript once, after the meeting:
python3 eval_talking_points.py full *.json --llm-url ...

# laya as the app asks it, and as one yes/no question per point:
python3 eval_talking_points.py laya *.json --laya-url http://laya.llama:8080
python3 eval_talking_points.py laya-yn *.json --laya-url http://laya.llama:8080
```

- `-v` lists every point with its verdict and when it was ticked.
- `--model` names the model if the endpoint needs one.
- `--laya-model` picks `english` or `multilingual`.
- `--laya-url` takes any service that speaks laya-serve's `/v1/systemone/batch`, such as Jev (#139).

## Keeping it in sync

The prompt is a copy of the app's `PromptBuilder.TALKING_POINTS_SYSTEM`. The decision-model
questions copy `DecisionTalkingPoints`, and the loop copies `TalkingPointChecker` and
`TalkingPointWindow` (15 s interval, 20 s overlap). Update the script when any of these change.

## Results (November 2026, #136)

On the three bundled meetings, 17 covered points and 17 not covered:

| Check | Right | Too early | False | Time per check |
|---|---|---|---|---|
| Muse Glimmer 30B (llama.cpp), `reasoning_strength: none` | 17 | 0 | 3 | 3.5–5 s |
| laya (english), as the app asks it | 14 | 1 | 2 | 0.03 s |

On a real 38-minute sales meeting with 11 points (not in the repository):

| Check | Right | Too early | False (of 3) | Time per check |
|---|---|---|---|---|
| laya, as shipped | 3 | 8 | 0 | 0.04 s |
| Glimmer with default reasoning | 4 | 0 | 0 | 28 s |
| Glimmer, `reasoning_strength: none` | 7 | 0 | 0 | 4.5 s |

Short meetings flatter laya. Over a long one it gets about 90 chances to tick each point too
early, and it takes most of them.

#!/usr/bin/env python3
"""Replay meetings the way ConvoScribe checks talking points while recording, and score the checks.

Each meeting is a JSON file (see README.md). The replay follows the app's TalkingPointChecker:
a line becomes available when it ends (the next line's start); every INTERVAL seconds the speech
since the last check, plus OVERLAP seconds before it, is checked against the points still open;
checks run one at a time, so a slow check slows the loop down; one more check runs when the
recording stops. A point counts as right when it is ticked at or after the moment it was first
discussed ("from"), too early when ticked before, and false when the meeting never covers it.

Methods:
  llm      the app's language-model prompt, live, against an OpenAI-compatible endpoint
  full     the same prompt over the whole transcript once, after the meeting
  laya     the shipped decision-model question (groups of 5 points + "none", top answer >= 0.5),
           live, against a laya-serve (or Jev-compatible) /v1/systemone/batch
  laya-yn  one yes/no question per point, scored against that point's answer on small talk

Standard library only.
"""
import argparse
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request

INTERVAL, OVERLAP = 15, 20
SPEECH_CHARS_PER_SECOND = 15

# The app's PromptBuilder.TALKING_POINTS_SYSTEM; keep in sync.
SYSTEM = (
    "You follow a conversation as it is being recorded and keep track of the points the user means "
    "to raise. You get the numbered points not covered yet and the latest part of the transcript. "
    "A point is covered only when the transcript actually discusses it, not when a word merely "
    "appears. Reply with only a JSON array, one object per covered point, giving its number and the "
    "time of the transcript line where it is discussed, for example "
    '[{"point": 2, "at": "12:34"}]. Reply [] when none is covered. Nothing else.'
)

# The app's DecisionTalkingPoints; keep in sync.
DECISION_INSTRUCTIONS = "Which talking point is this part of the conversation about?"
DECISION_NONE = "none of these: something else, small talk or logistics"
SMALL_TALK = [
    "[00:00] A: Okay, can everyone hear me? Let's give people another minute to join.",
    "[00:00] B: Sure, no problem. How was your weekend?",
    "[00:00] A: Great, thanks everyone, I think that's all for today.",
    "[00:00] A: Sorry, I was on mute. Can you see my screen now?",
    "[00:00] B: Yes, all good. Let me just grab a coffee.",
    "[00:00] A: Okay, I will send the notes around after this.",
]


def seconds(at):
    minutes, secs = at.split(":")
    return int(minutes) * 60 + int(secs)


def line_text(line, text=None):
    return f"[{line['at']}] {line['speaker']}: {line['text'] if text is None else text}"


def timeline(meeting):
    """The lines with start and end in seconds; a line ends where the next starts."""
    lines = meeting["lines"]
    starts = [seconds(l["at"]) for l in lines]
    ends = starts[1:] + [starts[-1] + max(3, len(lines[-1]["text"]) // SPEECH_CHARS_PER_SECOND)]
    return [dict(l, start=s, end=max(e, s + 1)) for l, s, e in zip(lines, starts, ends)]


def post(url, body, headers=None, timeout=900):
    request = urllib.request.Request(url, json.dumps(body).encode(), {"content-type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


# ---------- the language model ----------

def parse_answer(answer, count):
    """The point numbers in a reply, read leniently like the app's TalkingPointAnswer."""
    answer = re.sub(r"(?s)<think>.*?</think>", "", answer)
    match = re.search(r"\[.*\]", answer, re.S)
    if not match:
        return None
    try:
        items = json.loads(match.group(0))
    except ValueError:
        return None
    numbers = []
    for item in items:
        number = item.get("point") if isinstance(item, dict) else item
        if isinstance(number, (int, float)) and 1 <= int(number) <= count and int(number) not in numbers:
            numbers.append(int(number))
    return numbers


class Llm:
    def __init__(self, args):
        self.url = args.llm_url.rstrip("/") + "/chat/completions"
        self.model = args.model
        self.extra = json.loads(args.extra_fields) if args.extra_fields else {}
        self.headers = dict(h.split(":", 1) for h in args.header)
        key = os.environ.get("LLM_KEY")
        if key:
            self.headers["authorization"] = f"Bearer {key}"

    def ask(self, points, transcript):
        """The indexes of `points` the model says `transcript` covers, and the seconds it took."""
        user = "Points not covered yet:\n" + "\n".join(f"{i + 1}. {p}" for i, p in enumerate(points))
        user += "\n\nLatest transcript:\n\n" + transcript
        body = {"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
                "temperature": 0.3, "stream": False, **({"model": self.model} if self.model else {})}
        body.update(self.extra)
        started = time.time()
        reply = post(self.url, body, self.headers)
        answer = reply["choices"][0]["message"].get("content") or ""
        numbers = parse_answer(answer, len(points))
        if numbers is None:
            print(f"  unreadable answer: {answer[:200]!r}", file=sys.stderr)
            numbers = []
        return [n - 1 for n in numbers], time.time() - started


# ---------- the decision model ----------

def split_long(text, limit=900):
    parts, rest = [], text.strip()
    while len(rest) > limit:
        window = rest[: limit + 1]
        cut = max((m.end() for m in re.finditer(r"[.!?](?=\s)", window)), default=-1)
        if cut <= limit // 2:
            cut = window.rfind(" ") if window.rfind(" ") > limit // 2 else limit
        parts.append(rest[:cut].strip())
        rest = rest[cut:].strip()
    return parts + ([rest] if rest else [])


class Laya:
    def __init__(self, args, yes_no):
        self.url = args.laya_url.rstrip("/") + "/v1/systemone/batch"
        self.model = args.laya_model
        self.yes_no = yes_no
        self.lift = args.lift
        self.baseline = {}
        key = os.environ.get("LAYA_KEY")
        self.headers = {"authorization": f"Bearer {key}"} if key else {}

    def batch(self, states, questions):
        answers = []
        for i in range(0, len(states), 64):
            body = {"states": states[i:i + 64], "questions": questions, **({"model": self.model} if self.model else {})}
            answers += [r["answers"] for r in post(self.url, body, self.headers)["results"]]
        return answers

    def ask(self, points, window, all_points):
        started = time.time()
        states = [line_text(l, part) for l in window for part in split_long(l["text"])]
        covered = self.ask_yes_no(points, states) if self.yes_no else self.ask_choice(points, states, all_points)
        return covered, time.time() - started

    def ask_choice(self, points, states, all_points):
        """The shipped DecisionTalkingPoints: groups of 5 + none, filled with settled points, top answer >= 0.5."""
        others = [p for p in all_points if p not in points]
        groups = math.ceil(len(points) / 5)
        questions, next_other = {}, 0
        for g in range(groups):
            criteria = {str(i + 1): p for i, p in enumerate(points) if i % groups == g}
            for _ in range(min(5 - len(criteria), len(others))):
                j = next_other % len(others)
                next_other += 1
                criteria[str(len(points) + j + 1)] = others[j]
            criteria["none"] = DECISION_NONE
            questions[f"points{g + 1}"] = {"type": "choice", "instructions": DECISION_INSTRUCTIONS, "criteria": criteria}
        covered = set()
        for answer in self.batch(states, questions):
            for a in answer.values():
                choice = a["choice"]
                if choice.isdigit() and 1 <= int(choice) <= len(points) and a["probabilities"][choice] >= 0.5:
                    covered.add(int(choice) - 1)
        return sorted(covered)

    YES_NO = {"q": {"type": "choice", "instructions": "Does the transcript excerpt discuss the talking point?",
                    "criteria": {"yes": "yes, the excerpt discusses the talking point",
                                 "no": "no, the excerpt is about something else"}}}

    @staticmethod
    def log_odds(p):
        p = min(max(p, 1e-6), 1 - 1e-6)
        return math.log(p / (1 - p))

    def yes(self, point, states):
        framed = [f"Talking point: {point}\n\nTranscript excerpt:\n{s}" for s in states]
        return [self.log_odds(a["q"]["probabilities"]["yes"]) for a in self.batch(framed, self.YES_NO)]

    def ask_yes_no(self, points, states):
        """A point is covered where its log-odds of "yes" beat its mean on small talk by `lift`."""
        covered = []
        for i, point in enumerate(points):
            if point not in self.baseline:
                scores = self.yes(point, SMALL_TALK)
                self.baseline[point] = sum(scores) / len(scores)
            if max(self.yes(point, states)) - self.baseline[point] >= self.lift:
                covered.append(i)
        return covered


# ---------- replay and scoring ----------

def replay(meeting, check):
    lines = timeline(meeting)
    points = [p["text"] for p in meeting["points"]]
    ticked, latencies = {}, []
    clock, checked_up_to, stopped = 0.0, 0.0, False
    while not stopped:
        if clock + INTERVAL >= lines[-1]["end"]:
            clock, stopped = lines[-1]["end"], True
        else:
            clock += INTERVAL
        available = [l for l in lines if l["end"] <= clock or stopped]
        open_points = [i for i in range(len(points)) if i not in ticked]
        if not open_points or not any(l["start"] >= checked_up_to for l in available):
            continue
        window = [l for l in available if l["end"] >= checked_up_to - OVERLAP]
        covered, took = check([points[i] for i in open_points], window, points)
        latencies.append(took)
        clock += took
        for k in covered:
            ticked[open_points[k]] = clock
        checked_up_to = window[-1]["end"]
    return ticked, latencies


def full_pass(meeting, llm):
    lines = timeline(meeting)
    points = [p["text"] for p in meeting["points"]]
    covered, took = llm.ask(points, "\n".join(line_text(l) for l in lines))
    return {i: lines[-1]["end"] + took for i in covered}, [took]


def score(meeting, ticked):
    right = early = false = 0
    rows = []
    for i, point in enumerate(meeting["points"]):
        at = ticked.get(i)
        if at is None:
            verdict = "missed" if point["covered"] else ""
        elif not point["covered"]:
            verdict, false = "FALSE", false + 1
        elif at < seconds(point["from"]):
            verdict, early = "EARLY", early + 1
        else:
            verdict, right = "right", right + 1
        rows.append((point["text"], point.get("from") or "-", at, verdict))
    return right, early, false, rows


def mmss(t):
    return "-" if t is None else f"{int(t) // 60:02d}:{int(t) % 60:02d}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("method", choices=["llm", "full", "laya", "laya-yn"])
    parser.add_argument("meetings", nargs="+", help="meeting JSON files")
    parser.add_argument("--llm-url", default="http://localhost:8080/v1", help="OpenAI-compatible base URL (key: LLM_KEY)")
    parser.add_argument("--model", default="", help="model name, if the endpoint needs one")
    parser.add_argument("--extra-fields", default="", help='JSON merged into each request, e.g. \'{"reasoning_effort":"low"}\'')
    parser.add_argument("--header", action="append", default=[], help="extra header NAME:VALUE, e.g. X-Convoscribe-Reasoning:low")
    parser.add_argument("--laya-url", default="http://localhost:8080", help="laya-serve base URL (key: LAYA_KEY)")
    parser.add_argument("--laya-model", default="english")
    parser.add_argument("--lift", type=float, default=2.6, help="laya-yn: log-odds above small talk to tick")
    parser.add_argument("-v", "--verbose", action="store_true", help="show every point")
    args = parser.parse_args()

    if args.method in ("llm", "full"):
        engine = Llm(args)
        check = lambda points, window, _all: engine.ask(points, "\n".join(line_text(l) for l in window))
    else:
        engine = Laya(args, yes_no=args.method == "laya-yn")
        check = engine.ask

    totals = [0, 0, 0, 0, 0]
    for path in args.meetings:
        meeting = json.load(open(path, encoding="utf-8"))
        ticked, latencies = full_pass(meeting, engine) if args.method == "full" else replay(meeting, check)
        right, early, false, rows = score(meeting, ticked)
        covered = sum(p["covered"] for p in meeting["points"])
        uncovered = len(meeting["points"]) - covered
        mean = sum(latencies) / max(len(latencies), 1)
        print(f"{meeting.get('name', path):16} right {right}/{covered}, too early {early}, false {false}/{uncovered}"
              f" | {len(latencies)} checks, {mean:.2f} s mean, {max(latencies or [0]):.2f} s max")
        if args.verbose:
            for text, start, at, verdict in rows:
                print(f"    {verdict:6} from {start:5} ticked {mmss(at):5}  {text[:90]}")
        for k, v in enumerate((right, covered, early, false, uncovered)):
            totals[k] += v
    if len(args.meetings) > 1:
        print(f"{'all':16} right {totals[0]}/{totals[1]}, too early {totals[2]}, false {totals[3]}/{totals[4]}")


if __name__ == "__main__":
    main()

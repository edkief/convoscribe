#!/usr/bin/env bash
# Cut a GitHub release of the APK, in two halves:
#
#   prepare  (run locally) bump versionName (minor by default) and
#            versionCode in app/build.gradle.kts, commit
#            "chore(release): vX.Y.Z", tag vX.Y.Z, push both atomically.
#   publish  (run by Tekton on the vX.Y.Z tag push, or locally on a
#            checkout of the tag) ask an OpenAI-compatible LLM for release
#            notes written from the git log since the previous v* tag, run
#            the verify gate, build the release APK (signed with the shared
#            debug keystore until issue #16) and `gh release create` with the
#            APK and its sha256.
#
# sherpa-onnx is never built here. If its outputs are missing from app/,
# they are copied from $CONVOSCRIBE_SHERPA_PREBUILT (set in the builder
# image, see docker/android-builder.Containerfile) or extracted from the
# sherpa image with podman/docker (see scripts/build-images.sh).
#
# Usage: bash scripts/release.sh prepare [--major|--minor|--patch|--version X.Y.Z] [options]
#        bash scripts/release.sh publish [options]

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: bash scripts/release.sh prepare [--major|--minor|--patch|--version X.Y.Z] [options]
       bash scripts/release.sh publish [options]

prepare: bump the version, commit, tag vX.Y.Z and push the branch and tag.
The tag push makes Tekton run `publish`.

  --major             X.Y.Z -> (X+1).0.0
  --minor             X.Y.Z -> X.(Y+1).0 (default)
  --patch             X.Y.Z -> X.Y.(Z+1)
  --version X.Y.Z     release exactly this version
  --dry-run           print the new version, change nothing
  --allow-branch      allow releasing from a branch other than main
  -y, --yes           do not ask for confirmation before pushing

publish: build the APK for a vX.Y.Z tag and publish it as a GitHub release
with LLM-written release notes. Does nothing if the release already exists.

  --tag vX.Y.Z        tag to publish (default: $GIT_TAG, else the v* tag at
                      HEAD). HEAD must be the tagged commit.
  --dry-run           write notes and build the APK into dist/ but do not
                      publish; HEAD need not be tagged (the version comes
                      from app/build.gradle.kts)
  --notes-file FILE   use FILE as the release notes body instead of the LLM
  --no-verify         skip ktlint, unit tests and lint; only assembleRelease
  --skip-tests        skip the unit tests; keep ktlint and lint
  --draft             create the GitHub release as a draft
  -y, --yes           do not ask for confirmation before publishing

  -h, --help          show this help

Environment (publish):
  LLM_API_KEY         API key for the release-notes endpoint (required
                      unless --notes-file is given)
  LLM_BASE_URL        default http://llama-chat.llama:8080/v1
  LLM_MODEL           default llama-chat
  LLM_DISABLE_THINKING  1 (default) sends chat_template_kwargs.enable_thinking
                      = false (llama.cpp/vLLM); set 0 for other servers
  GH_TOKEN            used by gh when it is not logged in
  GIT_TAG             tag to publish when --tag is not given (set by Tekton)
  CONVOSCRIBE_SHERPA_PREBUILT  directory holding jniLibs/ and libs/ from the
                      sherpa image (set in the builder image)
  SHERPA_IMAGE        sherpa image to extract from when the libs are missing
                      (default: scripts/build-images.sh --print-sherpa-image)
Environment (prepare):
  RELEASE_BRANCH      branch to release from when HEAD is detached
                      (default main)
USAGE
}

die() {
  echo "release: $*" >&2
  exit 1
}

log() {
  echo "[release] $*"
}

command="${1:-}"
case "$command" in
  prepare | publish) shift ;;
  -h | --help)
    usage
    exit 0
    ;;
  *)
    echo "release: expected 'prepare' or 'publish', got '${command}'" >&2
    usage >&2
    exit 2
    ;;
esac

bump=minor
explicit_version=""
dry_run=false
notes_file=""
verify=true
run_tests=true
draft=false
allow_branch=false
assume_yes=false
publish_tag="${GIT_TAG:-}"
while [[ $# -gt 0 ]]; do
  case "$command:$1" in
    prepare:--major | prepare:--minor | prepare:--patch) bump="${1#--}" ;;
    prepare:--version)
      explicit_version="${2:?--version needs a value}"
      shift
      ;;
    prepare:--version=*) explicit_version="${1#*=}" ;;
    prepare:--allow-branch) allow_branch=true ;;
    publish:--tag)
      publish_tag="${2:?--tag needs a value}"
      shift
      ;;
    publish:--tag=*) publish_tag="${1#*=}" ;;
    publish:--notes-file)
      notes_file="${2:?--notes-file needs a value}"
      shift
      ;;
    publish:--notes-file=*) notes_file="${1#*=}" ;;
    publish:--no-verify) verify=false ;;
    publish:--skip-tests) run_tests=false ;;
    publish:--draft) draft=true ;;
    *:--dry-run) dry_run=true ;;
    *:-y | *:--yes) assume_yes=true ;;
    *:-h | *:--help)
      usage
      exit 0
      ;;
    *)
      echo "release: unknown option for $command: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

GRADLE_FILE=app/build.gradle.kts
TAG_GLOB='v[0-9]*.[0-9]*.[0-9]*'
# What an app release covers: everything except the sync server, which
# releases on its own server-v* tags (see .k8s-build.yaml).
APP_PATHS=(-- . ':(exclude)server')

# Version strings as read from the Gradle file.
read_versions() {
  current_name=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")
  current_code=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE")
  [[ "$current_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "could not read versionName X.Y.Z from $GRADLE_FILE (got '$current_name')."
  [[ "$current_code" =~ ^[0-9]+$ ]] || die "could not read versionCode from $GRADLE_FILE."
}

require_clean_tree() {
  if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    die "the working tree has uncommitted changes; commit or stash them first."
  fi
}

fetch_origin() {
  log "Fetching origin ..."
  if [[ "$(git rev-parse --is-shallow-repository)" == true ]]; then
    # The notes need the history back to the previous tag.
    git fetch --quiet --tags --unshallow origin
  else
    git fetch --quiet --tags origin
  fi
}

# version_gt A B: true when A > B in version order.
version_gt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

# ===========================================================================
# prepare
# ===========================================================================
prepare() {
  if [[ -n "$explicit_version" && ! "$explicit_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    die "--version must look like X.Y.Z, got '$explicit_version'."
  fi
  command -v git >/dev/null 2>&1 || die "git is not on PATH."
  require_clean_tree

  local branch
  branch=$(git symbolic-ref --quiet --short HEAD || true)
  if [[ -z "$branch" ]]; then
    branch="${RELEASE_BRANCH:-main}"
  fi
  if [[ "$branch" != main && "$allow_branch" == false ]]; then
    die "releasing from '$branch'; releases come from main (pass --allow-branch to override)."
  fi

  fetch_origin
  if git rev-parse --verify --quiet "refs/remotes/origin/$branch" >/dev/null; then
    if ! git merge-base --is-ancestor "origin/$branch" HEAD; then
      die "HEAD is behind or diverged from origin/$branch; pull first."
    fi
    local ahead
    ahead=$(git rev-list --count "origin/$branch..HEAD")
    if [[ "$ahead" -gt 0 ]]; then
      log "Note: HEAD is $ahead commit(s) ahead of origin/$branch; they will be pushed with the release."
    fi
  fi

  read_versions
  local prev_tag new_name new_code new_tag
  prev_tag=$(git describe --tags --abbrev=0 --match "$TAG_GLOB" HEAD 2>/dev/null || true)
  if [[ -n "$explicit_version" ]]; then
    new_name="$explicit_version"
  else
    local major minor patch
    IFS=. read -r major minor patch <<<"$current_name"
    case "$bump" in
      major) new_name="$((major + 1)).0.0" ;;
      minor) new_name="$major.$((minor + 1)).0" ;;
      patch) new_name="$major.$minor.$((patch + 1))" ;;
    esac
  fi
  new_code=$((current_code + 1))
  new_tag="v$new_name"

  if [[ -n "$prev_tag" ]] && ! version_gt "$new_name" "${prev_tag#v}"; then
    die "$new_tag is not newer than the previous release $prev_tag."
  fi
  if git rev-parse --verify --quiet "refs/tags/$new_tag" >/dev/null \
    || git ls-remote --exit-code --tags origin "refs/tags/$new_tag" >/dev/null 2>&1; then
    die "tag $new_tag already exists."
  fi
  if [[ -z "$(git log --no-merges --format=%h "${prev_tag:+$prev_tag..}HEAD" "${APP_PATHS[@]}")" ]]; then
    die "no commits since $prev_tag; nothing to release."
  fi

  log "Preparing $new_tag (versionName $current_name -> $new_name, versionCode $current_code -> $new_code)"
  log "Previous release: ${prev_tag:-none}"
  if [[ "$dry_run" == true ]]; then
    log "Dry run: nothing changed."
    return 0
  fi

  if [[ "$assume_yes" == false && -t 0 ]]; then
    local answer
    read -r -p "Commit, tag and push $new_tag to $branch? CI then builds and publishes it. [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]] || die "aborted; nothing changed."
  fi

  # Until the commit exists, any exit puts the Gradle file back.
  trap 'git checkout --quiet -- "$GRADLE_FILE"' EXIT
  sed -i \
    -e "s/^\( *versionName = \)\"$current_name\"/\1\"$new_name\"/" \
    -e "s/^\( *versionCode = \)$current_code\b/\1$new_code/" \
    "$GRADLE_FILE"
  grep -q "versionName = \"$new_name\"" "$GRADLE_FILE" && grep -q "versionCode = $new_code\b" "$GRADLE_FILE" \
    || die "failed to update the version in $GRADLE_FILE."

  git add "$GRADLE_FILE"
  git commit --quiet -m "chore(release): $new_tag"
  trap - EXIT
  git tag -a "$new_tag" -m "ConvoScribe $new_name"

  log "Pushing $branch and $new_tag ..."
  git push --atomic origin "HEAD:refs/heads/$branch" "refs/tags/$new_tag"
  log "Done: $new_tag pushed; Tekton builds and publishes the release from the tag."
}

# ===========================================================================
# publish
# ===========================================================================
publish() {
  if [[ -n "$notes_file" ]]; then
    [[ -s "$notes_file" ]] || die "--notes-file $notes_file is missing or empty."
    notes_file="$(cd "$(dirname "$notes_file")" && pwd)/$(basename "$notes_file")"
  fi

  LLM_BASE_URL="${LLM_BASE_URL:-http://llama-chat.llama:8080/v1}"
  LLM_MODEL="${LLM_MODEL:-llama-chat}"
  LLM_DISABLE_THINKING="${LLM_DISABLE_THINKING:-1}"

  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/env.sh"

  # -------------------------------------------------------------------------
  # Preflight: everything that can fail in seconds fails before the build.
  # -------------------------------------------------------------------------
  local tool
  for tool in git gh curl python3 sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH."
  done
  if [[ -z "$notes_file" && -z "${LLM_API_KEY:-}" ]]; then
    die "LLM_API_KEY is not set (or pass --notes-file)."
  fi
  if [[ "$dry_run" == false ]]; then
    gh auth status >/dev/null 2>&1 || die "gh is not authenticated (gh auth login, or set GH_TOKEN)."
  fi
  require_clean_tree
  fetch_origin
  read_versions

  # The tag to publish: --tag / $GIT_TAG, else the release tag at HEAD.
  local tag="$publish_tag"
  if [[ -z "$tag" ]]; then
    tag=$(git tag --points-at HEAD --list "$TAG_GLOB" | sort -V | tail -n 1)
  fi
  local untagged=false
  if [[ -z "$tag" ]]; then
    [[ "$dry_run" == true ]] || die "HEAD carries no vX.Y.Z tag; run 'release.sh prepare' first, check out the tag, or pass --tag."
    tag="v$current_name"
    untagged=true
    log "Dry run on an untagged HEAD: building as $tag."
  else
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "tag must look like vX.Y.Z, got '$tag'."
    git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || die "tag $tag does not exist."
    local tag_commit head_commit
    tag_commit=$(git rev-parse "$tag^{commit}")
    head_commit=$(git rev-parse HEAD)
    [[ "$tag_commit" == "$head_commit" ]] || die "$tag is $tag_commit but HEAD is $head_commit; check out the tag."
  fi
  local new_name="${tag#v}"
  [[ "$new_name" == "$current_name" ]] \
    || die "$tag does not match versionName $current_name in $GRADLE_FILE; tag the commit 'release.sh prepare' made."

  # The previous release, looked up from the parent: at the tag itself,
  # describe would return the tag being published.
  local prev_tag=""
  if [[ "$untagged" == true ]]; then
    prev_tag=$(git describe --tags --abbrev=0 --match "$TAG_GLOB" HEAD 2>/dev/null || true)
  elif git rev-parse --verify --quiet HEAD^ >/dev/null; then
    prev_tag=$(git describe --tags --abbrev=0 --match "$TAG_GLOB" HEAD^ 2>/dev/null || true)
  fi

  if [[ "$dry_run" == false ]] && gh release view "$tag" >/dev/null 2>&1; then
    log "The GitHub release $tag already exists; nothing to do."
    return 0
  fi

  log "Publishing $tag (versionName $new_name, versionCode $current_code)"
  log "Previous release: ${prev_tag:-none (notes cover the whole history)}"

  # -------------------------------------------------------------------------
  # sherpa-onnx outputs: take them prebuilt, never build them here.
  # -------------------------------------------------------------------------
  SHERPA_JAR=app/libs/sherpa-onnx-classes-1.13.8.jar
  SHERPA_JNI_DIR=app/src/main/jniLibs/arm64-v8a
  SHERPA_FILES=(
    "$SHERPA_JAR"
    "$SHERPA_JNI_DIR/libsherpa-onnx-jni.so"
    "$SHERPA_JNI_DIR/libonnxruntime.so"
    "$SHERPA_JNI_DIR/libQnnHtp.so"
    "$SHERPA_JNI_DIR/libonnxruntime4j_jni.so"
  )

  sherpa_ok() {
    local f
    for f in "${SHERPA_FILES[@]}"; do
      [[ -s "$f" ]] || return 1
    done
  }

  # install_sherpa_from <dir>: copy a /sherpa tree (jniLibs/, libs/) into app/.
  install_sherpa_from() {
    mkdir -p "$SHERPA_JNI_DIR" app/libs
    cp -f "$1"/jniLibs/arm64-v8a/*.so "$SHERPA_JNI_DIR/"
    cp -f "$1"/libs/*.jar app/libs/
  }

  if ! sherpa_ok; then
    if [[ -n "${CONVOSCRIBE_SHERPA_PREBUILT:-}" && -d "$CONVOSCRIBE_SHERPA_PREBUILT" ]]; then
      log "Copying prebuilt sherpa-onnx from $CONVOSCRIBE_SHERPA_PREBUILT ..."
      install_sherpa_from "$CONVOSCRIBE_SHERPA_PREBUILT"
    else
      local engine="" candidate
      for candidate in podman docker; do
        if command -v "$candidate" >/dev/null 2>&1; then
          engine="$candidate"
          break
        fi
      done
      [[ -n "$engine" ]] || die "sherpa-onnx outputs are missing and neither CONVOSCRIBE_SHERPA_PREBUILT nor podman/docker is available. Run: bash scripts/build-sherpa-onnx.sh"
      local image container extract_dir
      image="${SHERPA_IMAGE:-$(bash "$SCRIPT_DIR/build-images.sh" --print-sherpa-image)}"
      log "Extracting sherpa-onnx from $image ($engine) ..."
      "$engine" pull "$image"
      # The image is FROM scratch: create (never start) a container to copy from.
      container=$("$engine" create "$image" /none)
      extract_dir=$(mktemp -d)
      if ! "$engine" cp "$container:/sherpa/." "$extract_dir/"; then
        "$engine" rm "$container" >/dev/null
        die "could not copy /sherpa out of $image."
      fi
      "$engine" rm "$container" >/dev/null
      install_sherpa_from "$extract_dir"
      rm -rf "$extract_dir"
    fi
    sherpa_ok || die "sherpa-onnx outputs are still missing or empty after the copy."
  fi

  # -------------------------------------------------------------------------
  # Release notes (before the multi-minute build, so a bad endpoint or key
  # fails fast).
  # -------------------------------------------------------------------------
  DIST_DIR="$REPO_ROOT/dist"
  mkdir -p "$DIST_DIR"
  local NOTES_BODY="$DIST_DIR/release-notes-$tag.body.md"
  local NOTES="$DIST_DIR/release-notes-$tag.md"

  local range=HEAD
  if [[ -n "$prev_tag" ]]; then
    range="$prev_tag..HEAD"
  fi
  # Subjects and bodies, oldest first, each commit starting with a \x1e
  # record separator, without the release commits (prepare makes one per
  # tag), server-only commits, or Co-Authored-By/Signed-off-by trailers
  # (noise for the model).
  local log_filter=(--no-merges --invert-grep --grep='^chore(release): ')
  local commit_log commit_count prior_issues=""
  commit_log=$(git log "${log_filter[@]}" --reverse --format='%x1e%s%n%b' "$range" "${APP_PATHS[@]}" \
    | grep -v -E '^(Co-Authored-By|Signed-off-by):' || true)
  [[ -n "$commit_log" ]] || die "no commits since $prev_tag besides the release commit; nothing to release."
  commit_count=$(git rev-list "${log_filter[@]}" --count "$range" "${APP_PATHS[@]}")
  # Issues users already have something of: any "(#N)" in a commit subject
  # up to the previous release. The other issues in the log are new features.
  if [[ -n "$prev_tag" ]]; then
    prior_issues=$(git log --no-merges --format=%s "$prev_tag" "${APP_PATHS[@]}" | grep -oE '\(#[0-9]+\)' | tr -d '()#' | sort -un | tr '\n' ' ' || true)
  fi

  if [[ -n "$notes_file" ]]; then
    log "Using release notes from $notes_file"
    cp "$notes_file" "$NOTES_BODY"
  else
    log "Generating release notes from $commit_count commit(s) with $LLM_MODEL at $LLM_BASE_URL ..."
    COMMIT_LOG="$commit_log" PRIOR_ISSUES="$prior_issues" PREV_TAG="${prev_tag:-}" NEW_TAG="$tag" \
      LLM_BASE_URL="$LLM_BASE_URL" LLM_MODEL="$LLM_MODEL" \
      LLM_DISABLE_THINKING="$LLM_DISABLE_THINKING" \
      python3 - "$NOTES_BODY" <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

out_path = sys.argv[1]
prior_issues = set(os.environ.get("PRIOR_ISSUES", "").split())

# Commit types users cannot notice. The model is never shown them: given a
# test commit whose body describes a crash, it reports a fixed crash. Build
# commits stay only with an issue reference (e.g. release signing, #16);
# without one they are CI plumbing.
HIDDEN_TYPES = {"test", "docs", "ci", "style", "refactor", "chore"}
TYPE_RE = re.compile(r"^(\w+)(?:\([^)]*\))?!?:")
ISSUE_RE = re.compile(r"\(#(\d+)\)")

# Group the visible commits by issue, in order of first appearance, so the
# model sees each feature as a whole and is told whether users already have
# any of it, instead of guessing that from each commit's wording.
groups = {}
for record in os.environ["COMMIT_LOG"].split("\x1e"):
    if not record.strip():
        continue
    subject, _, commit_body = record.partition("\n")
    match = TYPE_RE.match(subject)
    kind = match.group(1).lower() if match else None
    issue = ISSUE_RE.search(subject)
    issue = issue.group(1) if issue else None
    if kind in HIDDEN_TYPES or (kind == "build" and issue is None):
        continue
    groups.setdefault(issue, []).append((kind, subject, commit_body.strip()))

if not groups:
    with open(out_path, "w") as f:
        f.write("Maintenance release with no user-visible changes.\n")
    sys.exit(0)

# Where each issue's bullet may go. Users have nothing of a new issue yet,
# so all of it is New, never Improved or Fixed; an issue whose commits are
# all build changes is Under the hood; a follow-up to a released issue goes
# wherever its commits fit.
build_only = {i for i, commits in groups.items() if i is not None and all(kind == "build" for kind, _, _ in commits)}
new_issues = [i for i in groups if i is not None and i not in prior_issues and i not in build_only]
# What the commits outside new features can feed. A commit without a type
# could be anything.
rest = [kind for i, commits in groups.items() if i not in new_issues and i not in build_only for kind, _, _ in commits]
allowed = []
if new_issues or rest:
    allowed.append("New")
if any(kind not in ("fix", "build") for kind in rest):
    allowed.append("Improved")
if any(kind in ("fix", None) for kind in rest):
    allowed.append("Fixed")
if build_only or "build" in rest:
    allowed.append("Under the hood")
homes = {i: {"New"} for i in new_issues}
homes.update({i: {"Under the hood"} for i in build_only})
homes.update({i: set(allowed) for i in groups if i is not None and i not in homes})


def render(with_bodies):
    parts = []
    for issue, commits in sorted(groups.items(), key=lambda kv: kv[0] is None):
        if issue is None:
            parts.append("## Commits without an issue reference (each stands alone)")
        elif issue in new_issues:
            parts.append(f"## #{issue}: NEW FEATURE, users have never had any of it")
        elif issue in build_only:
            parts.append(f"## #{issue}: BUILD CHANGE to how the app is built, signed or installed")
        else:
            parts.append(f"## #{issue}: FOLLOW-UP to a feature users already have from an earlier release")
        for _, subject, commit_body in commits:
            parts.append(f"* {subject}")
            if with_bodies and commit_body:
                parts.append("\n".join("  " + line for line in commit_body.splitlines()))
        parts.append("")
    return "\n".join(parts)


# Keep the prompt well inside the context window (a first release covers
# the whole history): drop the bodies first, then cut the end.
MAX_CHARS = 60000
log = render(with_bodies=True)
if len(log) > MAX_CHARS:
    log = render(with_bodies=False)
if len(log) > MAX_CHARS:
    log = log[:MAX_CHARS] + "\n[... later commits omitted ...]"

since = f"since {os.environ['PREV_TAG']}" if os.environ["PREV_TAG"] else "(first release)"

plan = [f"- Use only these `###` sections, in this order: {', '.join(f'`### {s}`' for s in allowed)}. No other sections."]
if new_issues:
    refs = ", ".join(f"#{i}" for i in new_issues)
    plan.append(f"- `### New` has exactly one bullet for each new feature: {refs}. Every part of a feature goes in its one bullet, never in a second bullet or under another section.")
if build_only:
    refs = ", ".join(f"#{i}" for i in build_only)
    plan.append(f"- `### Under the hood` has one bullet for each build change, {refs}, and only when it changes how the app is installed or updated. Never list a build change under `### New`.")
for section in ("New", "Improved", "Fixed", "Under the hood"):
    if section not in allowed:
        plan.append(f"- Nothing in this release belongs under `### {section}`: leave it out.")
plan = "\n".join(plan)

system = (
    "You write release notes for ConvoScribe, an Android app that records "
    "conversations and transcribes them on-device with speaker diarization "
    "(sherpa-onnx, optionally on the Qualcomm NPU). Readers are people who "
    "install the APK on their phone, not the developers."
)
user = f"""Write the release notes for ConvoScribe {os.environ['NEW_TAG']} {since} from the commit log below.

How to read the log:
- It is the development history between two releases. Users only ever had the previous release; none of the intermediate commits reached them. Describe the net difference between the previous release and this one, not the steps taken to get there.
- Commits follow Conventional Commits: `type(scope): subject`. `feat` adds behaviour, `fix` corrects it, `perf` speeds it up, `build` changes how the app is built or signed. The scope names the area of the app. Commits users cannot notice (tests, docs, refactors, CI) have already been removed.
- The commits are grouped by issue. Each `##` heading says whether that issue is a NEW FEATURE (users have never had any of it), a FOLLOW-UP to a feature they already have, or a BUILD CHANGE. Trust these labels.
- The commits of one issue are the steps of building it (data layer, service, UI); together they are one change for the user, not one change per commit. A fix, tweak or tuning inside a NEW FEATURE group is part of building that feature, not a fix or improvement users would notice (e.g. "add speaker identification" then "tune speaker identification sensitivity" is one New bullet).

Sections for this release:
{plan}
- A FOLLOW-UP group goes under `### Improved` for `feat`/`perf` commits and `### Fixed` for `fix` commits, or `### New` when it adds something clearly new to the feature.
- A commit without an issue reference: `feat` goes under `### New` or `### Improved`, `fix` under `### Fixed`, unless it adjusts something another commit in this log introduces, in which case it belongs to that change.

Rules:
- Output GitHub Markdown only: no title, no preamble, no closing remarks, no code fences around the whole answer.
- Start with one or two plain sentences summarising the release.
- Write exactly one bullet per issue: what the user can now do, in plain language, in one or two sentences, mentioning its main parts (e.g. "Model downloads now run in the background with a progress notification, resume after interruptions, wait for the network and retry on errors, and the setup screen follows their progress (#10).").
- End each bullet with its group's issue reference, e.g. "(#7)". Never give a bullet an issue reference its commits do not carry.
- Only describe what the commits say. Do not invent features, numbers or reasons.
- At most 12 bullets in total.

Commit log, grouped by issue (oldest first within a group; "* " starts each commit, followed by its indented body):

{log}
"""

BULLET_RE = re.compile(r"\s*[-*+] ")
REF_RE = re.compile(r"#(\d+)\b")


def ask(messages):
    body = {
        "model": os.environ["LLM_MODEL"],
        "messages": messages,
        "temperature": 0.3,
        "max_tokens": 3000,
    }
    if os.environ.get("LLM_DISABLE_THINKING", "1") == "1":
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(
        os.environ["LLM_BASE_URL"].rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["LLM_API_KEY"],
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError as e:
        sys.exit(f"release: LLM request failed: HTTP {e.code}: {e.read().decode(errors='replace')[:500]}")
    except (urllib.error.URLError, TimeoutError) as e:
        sys.exit(f"release: LLM request failed: {e}")
    try:
        choice = data["choices"][0]
        text = choice["message"].get("content") or ""
    except (KeyError, IndexError, TypeError):
        sys.exit(f"release: unexpected LLM response: {json.dumps(data)[:500]}")
    # Reasoning models may inline their thinking; drop it and any wrapping fence.
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    text = re.sub(r"^```(?:markdown|md)?\s*\n(.*)\n```$", r"\1", text, flags=re.S).strip()
    if choice.get("finish_reason") == "length":
        sys.exit("release: the LLM hit max_tokens before finishing; retry, or pass --notes-file.")
    if not text:
        sys.exit("release: the LLM returned empty release notes; retry, or pass --notes-file.")
    return text


def tidy(text):
    """Keep the allowed sections, each bullet only in a section its issues
    may go to (which also drops bullets citing issues not in the log), and
    no empty sections. Returns the notes and the bullet count per issue."""
    intro_marker = object()
    intro, sections, current, keep = [], [], intro_marker, True
    for line in text.splitlines():
        if line.startswith("### "):
            name = next((s for s in allowed if line[4:].strip().lower().startswith(s.lower())), None)
            current = [name, []] if name else None
            if current:
                sections.append(current)
            keep = True
        elif current is intro_marker:
            intro.append(line)
        elif current is not None:
            if BULLET_RE.match(line):
                refs = REF_RE.findall(line)
                keep = not refs or any(current[0] in homes.get(r, ()) for r in refs)
            elif not line.strip():
                keep = True
            if keep:
                current[1].append(line)
    counts = {}
    parts = ["\n".join(intro).strip()]
    for name in allowed:
        lines = [line for sec_name, sec_lines in sections if sec_name == name for line in sec_lines]
        if not any(BULLET_RE.match(line) for line in lines):
            continue
        for line in lines:
            if BULLET_RE.match(line):
                for r in set(REF_RE.findall(line)):
                    counts[r] = counts.get(r, 0) + 1
        parts.append(f"### {name}\n\n" + "\n".join(lines).strip())
    return "\n\n".join(p for p in parts if p), counts


# The model still sometimes files part of a new feature under Improved or
# Fixed, splits one feature over several bullets or makes up an issue
# reference. tidy() drops the misplaced bullets; split or missing features
# go back to the model, at most twice, before taking what it gave.
messages = [
    {"role": "system", "content": system},
    {"role": "user", "content": user},
]
for attempt in range(3):
    raw = ask(messages)
    text, counts = tidy(raw)
    problems = [f"#{i} has {n} bullets; merge them into one bullet." for i, n in counts.items() if n > 1]
    problems += [f"#{i} is missing; give it one bullet under `### New`." for i in new_issues if i not in counts]
    if not problems:
        break
    print(f"[release] notes attempt {attempt + 1}: {' '.join(problems)}", file=sys.stderr)
    messages += [
        {"role": "assistant", "content": raw},
        {"role": "user", "content": "Write the full release notes again, following every rule above and fixing these problems:\n" + "\n".join(f"- {p}" for p in problems)},
    ]

with open(out_path, "w") as f:
    f.write(text + "\n")
PY
  fi

  # -------------------------------------------------------------------------
  # Build. The version is already committed, so nothing in the tree changes.
  # -------------------------------------------------------------------------
  # In CI the pod's memory limit covers every JVM. By default the Kotlin
  # daemon inherits the Gradle daemon's -Xmx4g as a second heap, which with
  # lint and the test JVM went past the 8Gi limit (OOMKilled). Compile
  # in-process and cap parallel workers instead.
  local gradle_args=()
  if [[ "${CI:-}" == true ]]; then
    gradle_args=(--max-workers=2 -Pkotlin.compiler.execution.strategy=in-process)
    log "CI: ${gradle_args[*]}"
  fi
  ./gradlew --stop >/dev/null 2>&1 || true
  if [[ "$verify" == true ]]; then
    local gate=(ktlintCheck testDebugUnitTest lintDebug)
    if [[ "$run_tests" == false ]]; then
      gate=(ktlintCheck lintDebug)
    fi
    log "Building (${gate[*]} assembleRelease) ..."
    [[ "$run_tests" == true ]] || log "Unit tests skipped (--skip-tests)."
    ./gradlew "${gradle_args[@]}" "${gate[@]}" assembleRelease
  else
    log "Building (assembleRelease only, --no-verify) ..."
    ./gradlew "${gradle_args[@]}" assembleRelease
  fi
  ./gradlew --stop >/dev/null 2>&1 || true

  local APK_SRC=app/build/outputs/apk/release/app-release.apk
  if [[ ! -f "$APK_SRC" ]]; then
    if [[ -f app/build/outputs/apk/release/app-release-unsigned.apk ]]; then
      die "the release APK is unsigned (app/debug.keystore missing?)."
    fi
    die "expected the APK at $APK_SRC but it is not there."
  fi
  local built_name
  built_name=$(grep -oP '"versionName"\s*:\s*"\K[^"]+' app/build/outputs/apk/release/output-metadata.json || true)
  [[ "$built_name" == "$new_name" ]] || die "the APK reports versionName '$built_name', expected '$new_name'."

  local APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
  if [[ -x "$APKSIGNER" ]]; then
    "$APKSIGNER" verify "$APK_SRC" || die "apksigner could not verify $APK_SRC."
  fi

  local APK_NAME="convoscribe-$new_name.apk"
  local APK="$DIST_DIR/$APK_NAME"
  cp -f "$APK_SRC" "$APK"
  (cd "$DIST_DIR" && sha256sum "$APK_NAME" >"$APK_NAME.sha256")
  local sha256
  sha256=$(cut -d' ' -f1 "$APK.sha256")

  # Final notes: the LLM (or --notes-file) body, install instructions and the
  # full commit list for anyone who wants the detail.
  {
    cat "$NOTES_BODY"
    echo
    echo "---"
    echo
    echo "**Install:** download \`$APK_NAME\` on the phone and open it, or run \`adb install -r $APK_NAME\`."
    echo
    echo "sha256: \`$sha256\`"
    echo
    echo "<details><summary>${commit_count} commit(s) ${prev_tag:+since $prev_tag}</summary>"
    echo
    git log "${log_filter[@]}" --reverse --format='- %s (%h)' "$range" "${APP_PATHS[@]}"
    echo
    echo "</details>"
  } >"$NOTES"
  rm -f "$NOTES_BODY"

  echo
  echo "================ $tag release notes ================"
  cat "$NOTES"
  echo "========================================================"
  echo "APK: $APK ($(stat -c '%s' "$APK") bytes)"
  echo "sha256: $sha256"
  echo

  if [[ "$dry_run" == true ]]; then
    log "Dry run: nothing published."
    return 0
  fi

  if [[ "$assume_yes" == false && -t 0 ]]; then
    local answer
    read -r -p "Publish the GitHub release $tag? [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]] || die "aborted. Edit $NOTES and pass it with --notes-file to reuse it."
  fi

  local gh_args=(--title "ConvoScribe $new_name" --notes-file "$NOTES" --verify-tag)
  if [[ "$draft" == true ]]; then
    gh_args+=(--draft)
  fi
  log "Creating GitHub release $tag ..."
  if ! gh release create "$tag" "$APK" "$APK.sha256" "${gh_args[@]}"; then
    echo "release: creating the GitHub release failed. Retry with:" >&2
    echo "    gh release create $tag '$APK' '$APK.sha256' --title 'ConvoScribe $new_name' --notes-file '$NOTES' --verify-tag" >&2
    exit 1
  fi
  log "Done: $tag"
}

"$command"

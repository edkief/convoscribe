#!/usr/bin/env bash
# Cut a GitHub release of the APK:
#
#   1. bump versionName (minor by default) and versionCode in
#      app/build.gradle.kts,
#   2. ask an OpenAI-compatible LLM for release notes written from the git
#      log since the previous v* tag,
#   3. run the verify gate and build the release APK (signed with the shared
#      debug keystore until issue #16),
#   4. commit "chore(release): vX.Y.Z", tag vX.Y.Z, push both atomically,
#   5. `gh release create` with the APK and its sha256.
#
# sherpa-onnx is never built here. If its outputs are missing from app/,
# they are copied from $CONVOSCRIBE_SHERPA_PREBUILT (set in the builder
# image, see docker/android-builder.Containerfile) or extracted from the
# sherpa image with podman/docker (see scripts/build-images.sh).
#
# Usage: bash scripts/release.sh [--major|--minor|--patch|--version X.Y.Z] [options]

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: bash scripts/release.sh [--major|--minor|--patch|--version X.Y.Z] [options]

Bump the version, build the release APK and publish it as a GitHub release
with LLM-written release notes.

Version (default --minor):
  --major             X.Y.Z -> (X+1).0.0
  --minor             X.Y.Z -> X.(Y+1).0
  --patch             X.Y.Z -> X.Y.(Z+1)
  --version X.Y.Z     release exactly this version

Options:
  --dry-run           write notes and build the APK into dist/, but do not
                      commit, tag, push or publish (the version bump is undone)
  --notes-file FILE   use FILE as the release notes body instead of the LLM
  --no-verify         skip ktlint, unit tests and lint; only assembleRelease
  --draft             create the GitHub release as a draft
  --allow-branch      allow releasing from a branch other than main
  -y, --yes           do not ask for confirmation before publishing
  -h, --help          show this help

Environment:
  LLM_API_KEY         API key for the release-notes endpoint (required
                      unless --notes-file is given)
  LLM_BASE_URL        default http://llama-chat.llama:8080/v1
  LLM_MODEL           default llama-chat
  LLM_DISABLE_THINKING  1 (default) sends chat_template_kwargs.enable_thinking
                      = false (llama.cpp/vLLM); set 0 for other servers
  RELEASE_BRANCH      branch to release from when HEAD is detached
                      (default main)
  CONVOSCRIBE_SHERPA_PREBUILT  directory holding jniLibs/ and libs/ from the
                      sherpa image (set in the builder image)
  SHERPA_IMAGE        sherpa image to extract from when the libs are missing
                      (default: scripts/build-images.sh --print-sherpa-image)
  GH_TOKEN            used by gh when it is not logged in
USAGE
}

bump=minor
explicit_version=""
dry_run=false
notes_file=""
verify=true
draft=false
allow_branch=false
assume_yes=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --major | --minor | --patch) bump="${1#--}" ;;
    --version)
      explicit_version="${2:?--version needs a value}"
      shift
      ;;
    --version=*) explicit_version="${1#*=}" ;;
    --dry-run) dry_run=true ;;
    --notes-file)
      notes_file="${2:?--notes-file needs a value}"
      shift
      ;;
    --notes-file=*) notes_file="${1#*=}" ;;
    --no-verify) verify=false ;;
    --draft) draft=true ;;
    --allow-branch) allow_branch=true ;;
    -y | --yes) assume_yes=true ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "release: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

die() {
  echo "release: $*" >&2
  exit 1
}

log() {
  echo "[release] $*"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

if [[ -n "$notes_file" ]]; then
  [[ -s "$notes_file" ]] || die "--notes-file $notes_file is missing or empty."
  notes_file="$(cd "$(dirname "$notes_file")" && pwd)/$(basename "$notes_file")"
fi
if [[ -n "$explicit_version" && ! "$explicit_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  die "--version must look like X.Y.Z, got '$explicit_version'."
fi

LLM_BASE_URL="${LLM_BASE_URL:-http://llama-chat.llama:8080/v1}"
LLM_MODEL="${LLM_MODEL:-llama-chat}"
LLM_DISABLE_THINKING="${LLM_DISABLE_THINKING:-1}"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/env.sh"

# ---------------------------------------------------------------------------
# Preflight: everything that can fail in seconds fails before the build.
# ---------------------------------------------------------------------------
for tool in git gh curl python3 sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH."
done
if [[ -z "$notes_file" && -z "${LLM_API_KEY:-}" ]]; then
  die "LLM_API_KEY is not set (or pass --notes-file)."
fi
if [[ "$dry_run" == false ]]; then
  gh auth status >/dev/null 2>&1 || die "gh is not authenticated (gh auth login, or set GH_TOKEN)."
fi

GRADLE_FILE=app/build.gradle.kts
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  die "the working tree has uncommitted changes; commit or stash them first."
fi

branch=$(git symbolic-ref --quiet --short HEAD || true)
if [[ -z "$branch" ]]; then
  # Detached HEAD, as in a CI checkout of a commit.
  branch="${RELEASE_BRANCH:-main}"
fi
if [[ "$branch" != main && "$allow_branch" == false ]]; then
  die "releasing from '$branch'; releases come from main (pass --allow-branch to override)."
fi

log "Fetching origin ..."
if [[ "$(git rev-parse --is-shallow-repository)" == true ]]; then
  # The notes need the history back to the previous tag.
  git fetch --quiet --tags --unshallow origin
else
  git fetch --quiet --tags origin
fi
if git rev-parse --verify --quiet "refs/remotes/origin/$branch" >/dev/null; then
  if ! git merge-base --is-ancestor "origin/$branch" HEAD; then
    die "HEAD is behind or diverged from origin/$branch; pull first."
  fi
  ahead=$(git rev-list --count "origin/$branch..HEAD")
  if [[ "$ahead" -gt 0 ]]; then
    log "Note: HEAD is $ahead commit(s) ahead of origin/$branch; they will be pushed with the release."
  fi
fi

# ---------------------------------------------------------------------------
# Versions.
# ---------------------------------------------------------------------------
current_name=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")
current_code=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE")
[[ "$current_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "could not read versionName X.Y.Z from $GRADLE_FILE (got '$current_name')."
[[ "$current_code" =~ ^[0-9]+$ ]] || die "could not read versionCode from $GRADLE_FILE."

prev_tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' HEAD 2>/dev/null || true)

if [[ -n "$explicit_version" ]]; then
  new_name="$explicit_version"
else
  IFS=. read -r major minor patch <<<"$current_name"
  case "$bump" in
    major) new_name="$((major + 1)).0.0" ;;
    minor) new_name="$major.$((minor + 1)).0" ;;
    patch) new_name="$major.$minor.$((patch + 1))" ;;
  esac
fi
new_code=$((current_code + 1))
new_tag="v$new_name"

# version_gt A B: true when A > B in version order.
version_gt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}
if [[ -n "$prev_tag" ]] && ! version_gt "$new_name" "${prev_tag#v}"; then
  die "$new_tag is not newer than the previous release $prev_tag."
fi
if git rev-parse --verify --quiet "refs/tags/$new_tag" >/dev/null \
  || git ls-remote --exit-code --tags origin "refs/tags/$new_tag" >/dev/null 2>&1; then
  die "tag $new_tag already exists."
fi

log "Releasing $new_tag (versionName $current_name -> $new_name, versionCode $current_code -> $new_code)"
log "Previous release: ${prev_tag:-none (notes cover the whole history)}"

# ---------------------------------------------------------------------------
# sherpa-onnx outputs: take them prebuilt, never build them here.
# ---------------------------------------------------------------------------
SHERPA_JAR=app/libs/sherpa-onnx-classes-1.13.8.jar
SHERPA_JNI_DIR=app/src/main/jniLibs/arm64-v8a
SHERPA_FILES=(
  "$SHERPA_JAR"
  "$SHERPA_JNI_DIR/libsherpa-onnx-jni.so"
  "$SHERPA_JNI_DIR/libonnxruntime.so"
  "$SHERPA_JNI_DIR/libQnnHtp.so"
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
    engine=""
    for candidate in podman docker; do
      if command -v "$candidate" >/dev/null 2>&1; then
        engine="$candidate"
        break
      fi
    done
    [[ -n "$engine" ]] || die "sherpa-onnx outputs are missing and neither CONVOSCRIBE_SHERPA_PREBUILT nor podman/docker is available. Run: bash scripts/build-sherpa-onnx.sh"
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

# ---------------------------------------------------------------------------
# Release notes (before the multi-minute build, so a bad endpoint or key
# fails fast).
# ---------------------------------------------------------------------------
DIST_DIR="$REPO_ROOT/dist"
mkdir -p "$DIST_DIR"
NOTES_BODY="$DIST_DIR/release-notes-$new_tag.body.md"
NOTES="$DIST_DIR/release-notes-$new_tag.md"

if [[ -n "$prev_tag" ]]; then
  range="$prev_tag..HEAD"
else
  range=HEAD
fi
# Subjects and bodies, oldest first, without Co-Authored-By/Signed-off-by
# trailers (noise for the model).
commit_log=$(git log --no-merges --reverse --format='* %s%n%b' "$range" \
  | grep -v -E '^(Co-Authored-By|Signed-off-by):' || true)
[[ -n "$commit_log" ]] || die "no commits since $prev_tag; nothing to release."
commit_count=$(git rev-list --no-merges --count "$range")

if [[ -n "$notes_file" ]]; then
  log "Using release notes from $notes_file"
  cp "$notes_file" "$NOTES_BODY"
else
  log "Generating release notes from $commit_count commit(s) with $LLM_MODEL at $LLM_BASE_URL ..."
  COMMIT_LOG="$commit_log" PREV_TAG="${prev_tag:-}" NEW_TAG="$new_tag" \
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
log = os.environ["COMMIT_LOG"]
# Keep the prompt well inside the context window; the oldest commits go
# first when the log is huge (a first release covers the whole history).
MAX_CHARS = 60000
if len(log) > MAX_CHARS:
    log = "[... older commits omitted ...]\n" + log[-MAX_CHARS:]

since = f"since {os.environ['PREV_TAG']}" if os.environ["PREV_TAG"] else "(first release)"

system = (
    "You write release notes for ConvoScribe, an Android app that records "
    "conversations and transcribes them on-device with speaker diarization "
    "(sherpa-onnx, optionally on the Qualcomm NPU). Readers are people who "
    "install the APK on their phone, not the developers."
)
user = f"""Write the release notes for ConvoScribe {os.environ['NEW_TAG']} {since} from the commit log below.

Rules:
- Output GitHub Markdown only: no title, no preamble, no closing remarks, no code fences around the whole answer.
- Start with one or two plain sentences summarising the release.
- Then use these `###` sections, in this order, leaving out any that would be empty: `### New`, `### Improved`, `### Fixed`.
- Each bullet describes what changed for the user, in plain language, in one line. Merge commits that belong to the same feature into one bullet. Keep the issue reference when commits give one, e.g. "(#7)".
- Leave out changes users cannot notice (refactors, tests, docs, CI, build scripts, formatting), unless they change how the app is installed or updated; those go in a final short `### Under the hood` section only if worth mentioning.
- Only describe what the commits say. Do not invent features, numbers or reasons.
- At most 12 bullets in total.

Commit log (oldest first; "* " starts each commit, followed by its body):

{log}
"""

body = {
    "model": os.environ["LLM_MODEL"],
    "messages": [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ],
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

with open(out_path, "w") as f:
    f.write(text + "\n")
PY
fi

# ---------------------------------------------------------------------------
# Bump the version and build. Until the release commit exists, any exit
# puts the Gradle file back.
# ---------------------------------------------------------------------------
restore_gradle() {
  git checkout --quiet -- "$GRADLE_FILE"
}
trap restore_gradle EXIT

sed -i \
  -e "s/^\( *versionName = \)\"$current_name\"/\1\"$new_name\"/" \
  -e "s/^\( *versionCode = \)$current_code\b/\1$new_code/" \
  "$GRADLE_FILE"
grep -q "versionName = \"$new_name\"" "$GRADLE_FILE" && grep -q "versionCode = $new_code\b" "$GRADLE_FILE" \
  || die "failed to update the version in $GRADLE_FILE."

./gradlew --stop >/dev/null 2>&1 || true
if [[ "$verify" == true ]]; then
  log "Building (ktlintCheck testDebugUnitTest lintDebug assembleRelease) ..."
  ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleRelease
else
  log "Building (assembleRelease only, --no-verify) ..."
  ./gradlew assembleRelease
fi
./gradlew --stop >/dev/null 2>&1 || true

APK_SRC=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$APK_SRC" ]]; then
  if [[ -f app/build/outputs/apk/release/app-release-unsigned.apk ]]; then
    die "the release APK is unsigned (app/debug.keystore missing?)."
  fi
  die "expected the APK at $APK_SRC but it is not there."
fi
built_name=$(grep -oP '"versionName"\s*:\s*"\K[^"]+' app/build/outputs/apk/release/output-metadata.json || true)
[[ "$built_name" == "$new_name" ]] || die "the APK reports versionName '$built_name', expected '$new_name'."

APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  "$APKSIGNER" verify "$APK_SRC" || die "apksigner could not verify $APK_SRC."
fi

APK_NAME="convoscribe-$new_name.apk"
APK="$DIST_DIR/$APK_NAME"
cp -f "$APK_SRC" "$APK"
(cd "$DIST_DIR" && sha256sum "$APK_NAME" >"$APK_NAME.sha256")
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
  git log --no-merges --reverse --format='- %s (%h)' "$range"
  echo
  echo "</details>"
} >"$NOTES"
rm -f "$NOTES_BODY"

echo
echo "================ $new_tag release notes ================"
cat "$NOTES"
echo "========================================================"
echo "APK: $APK ($(stat -c '%s' "$APK") bytes)"
echo "sha256: $sha256"
echo

if [[ "$dry_run" == true ]]; then
  log "Dry run: $GRADLE_FILE restored; nothing committed, tagged, pushed or published."
  exit 0
fi

if [[ "$assume_yes" == false && -t 0 ]]; then
  read -r -p "Commit, tag, push and publish $new_tag? [y/N] " answer
  [[ "$answer" =~ ^[Yy]$ ]] || die "aborted; $GRADLE_FILE restored. Edit $NOTES and pass it with --notes-file to reuse it."
fi

# ---------------------------------------------------------------------------
# Commit, tag, push, publish.
# ---------------------------------------------------------------------------
git add "$GRADLE_FILE"
git commit --quiet -m "chore(release): $new_tag"
trap - EXIT
git tag -a "$new_tag" -m "ConvoScribe $new_name"

log "Pushing $branch and $new_tag ..."
git push --atomic origin "HEAD:refs/heads/$branch" "refs/tags/$new_tag"

gh_args=(--title "ConvoScribe $new_name" --notes-file "$NOTES" --verify-tag)
if [[ "$draft" == true ]]; then
  gh_args+=(--draft)
fi
log "Creating GitHub release $new_tag ..."
if ! gh release create "$new_tag" "$APK" "$APK.sha256" "${gh_args[@]}"; then
  echo "release: the tag is pushed but creating the GitHub release failed. Retry with:" >&2
  echo "    gh release create $new_tag '$APK' '$APK.sha256' --title 'ConvoScribe $new_name' --notes-file '$NOTES' --verify-tag" >&2
  exit 1
fi
log "Done: $new_tag"

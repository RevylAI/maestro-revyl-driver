#!/usr/bin/env bash
# Measures this runner against Maestro's own public e2e flows.
# Without --session it only classifies flows offline (supported subset or the first
# rejected command). With --session it also runs every supported flow against that
# existing Revyl session and checks the outcome against the flow's passing/failing tag.
set -euo pipefail

usage() {
  echo "Usage: conformance/maestro-e2e.sh --platform android|ios --workspace demo_app|wikipedia [--session UUID] [--maestro-ref REF] [--out DIR]" >&2
  exit 2
}

platform="" workspace="" session="" ref="cli-2.10.0" out=".revyl-maestro/conformance"
while [ $# -gt 0 ]; do
  case "$1" in
    --platform) platform="$2"; shift 2 ;;
    --workspace) workspace="$2"; shift 2 ;;
    --session) session="$2"; shift 2 ;;
    --maestro-ref) ref="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) usage ;;
  esac
done
case "$platform" in android|ios) ;; *) usage ;; esac
case "$workspace" in
  demo_app) flows_path="e2e/demo_app/.maestro" ;;
  wikipedia) flows_path="e2e/workspaces/wikipedia" ;;
  *) usage ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
runner="$root/build/install/revyl-maestro/bin/revyl-maestro"
[ -x "$runner" ] || { echo "Build the runner first: ./gradlew --no-daemon installDist" >&2; exit 2; }

checkout="$root/.revyl-maestro/upstream-$ref"
if [ ! -d "$checkout/.git" ]; then
  git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse https://github.com/mobile-dev-inc/maestro.git "$checkout"
  git -C "$checkout" sparse-checkout set e2e >/dev/null
fi

mkdir -p "$out"
report="$out/$workspace-$platform.tsv"
printf 'flow\texpected\tpreflight\tlive\tverdict\n' > "$report"
other_platforms="$(printf 'android\nios\nweb\n' | grep -vx "$platform" | paste -sd'|')"

while IFS= read -r flow; do
  header="$(awk '/^---/{exit} {print}' "$flow")"
  expected="$(printf '%s\n' "$header" | grep -oE '^\s*-\s*(passing|failing)\s*$' | tr -d ' -' | head -1 || true)"
  [ -n "$expected" ] || continue
  if printf '%s\n' "$header" | grep -qE "^\s*-\s*($other_platforms)\s*$"; then continue; fi
  name="${flow#"$checkout/$flows_path/"}"
  if preflight="$(env -u REVYL_API_KEY "$runner" --validate-only --platform "$platform" "$flow" 2>&1)"; then
    preflight_result="supported"
  else
    preflight_result="$(printf '%s' "$preflight" | grep -oE 'Rejected command: [A-Za-z]+' | sed 's/Rejected command: //' || true)"
    if [ -z "$preflight_result" ] && printf '%s' "$preflight" | grep -q 'simple parameter references'; then preflight_result="JavaScript"; fi
    preflight_result="unsupported:${preflight_result:-FlowOptionOrHook}"
  fi
  live="-" verdict="-"
  if [ -n "$session" ] && [ "$preflight_result" = "supported" ]; then
    if (cd "$out" && "$runner" --session "$session" --platform "$platform" "$flow" >/dev/null 2>&1); then live="passed"; else live="failed"; fi
    if { [ "$expected" = passing ] && [ "$live" = passed ]; } || { [ "$expected" = failing ] && [ "$live" = failed ]; }; then verdict="ok"; else verdict="mismatch"; fi
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$expected" "$preflight_result" "$live" "$verdict" >> "$report"
done < <(find "$checkout/$flows_path" -name '*.yaml' ! -name config.yaml | sort)

total=$(($(wc -l < "$report") - 1))
supported=$(awk -F'\t' 'NR>1 && $3=="supported"' "$report" | wc -l)
echo "$workspace on $platform: $supported/$total flows inside the supported subset (Maestro $ref)."
awk -F'\t' 'NR>1 && $3!="supported" {print $3}' "$report" | sort | uniq -c | sort -rn | sed 's/^/  /'
if [ -n "$session" ]; then
  ok=$(awk -F'\t' 'NR>1 && $5=="ok"' "$report" | wc -l)
  echo "Live: $ok/$supported supported flows matched their passing/failing tag."
  awk -F'\t' 'NR>1 && $5=="mismatch" {print "  mismatch: " $1 " (expected " $2 ", " $4 ")"}' "$report"
fi
echo "Report: $report"

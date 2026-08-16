#!/usr/bin/env bash
#
# Runs the port gates — the four (five, with Swift) suites that hold the language back ends to the
# C# side. One definition of "the gates", called by CI and by a person, so the two cannot disagree.
#
#   bash tools/port-gates.sh                    # everything this machine can run
#   bash tools/port-gates.sh kotlin typescript  # only these
#
# The thing this script exists to get right, twice:
#
#   1. A gate that did not run must never read as a gate that passed. Every skip is printed as
#      SKIPPED with its reason, and the summary counts skips separately. The project has been bitten
#      by a summary that looked green while the run had been aborted (`dotnet test`, 2026-08-10);
#      this reads exit codes and nothing else.
#
#   2. Five Kotlin modules and five Swift test targets read their corpus from
#      `../../ISO15118ConformanceTests.Simulation/Vectors/` — the CONFORMANCE repository, the parent
#      that carries this one as a submodule. A checkout of EVSimulatorApp on its own cannot pass
#      them: `SessionTrace.load` ends at `session trace not found at …`, once per test. So the
#      corpus is checked once, up front, and its absence is named rather than discovered fifty times.
#
# What none of them needs is the ISO schemas: every codec here is generated and checked in, and the
# vectors are checked in too. `download-schemas.sh` is for the C# build, not for these.

set -uo pipefail          # deliberately not -e: every gate runs, and all failures get reported

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ALL=(kotlin swift typescript capacitor app)
WANTED=("$@")
[ ${#WANTED[@]} -eq 0 ] && WANTED=("${ALL[@]}")

CORPUS='../../ISO15118ConformanceTests.Simulation/Vectors/Session.iso2-ac-eim.trace.json'
declare -A RESULT

run() {                   # run <name> <command...>
    local name=$1; shift
    echo; echo "===== $name ====="
    "$@"
    local code=$?
    RESULT[$name]=$([ $code -eq 0 ] && echo PASSED || echo "FAILED (exit $code)")
}

skip() { RESULT[$1]="SKIPPED — $2"; echo; echo "===== $1 ====="; echo "skipped: $2"; }

# `swift test` is not to be trusted by its exit code alone. Measured on a macOS runner, 2026-08-16:
# it returned 0 while its own output said `Executed 222 tests, with 13 failures` and
# `Test Suite 'All tests' failed`. This gate went green over eight genuinely failing tests, which is
# the same lie as an aborted `dotnet test` printing per-assembly "Bestanden!" lines — just one layer
# further down. So the summary is read as well, and either signal fails the gate.
swift_gate() {

    local log; log=$(mktemp)

    swift test --package-path swift 2>&1 | tee "$log"
    local code=${PIPESTATUS[0]}

    if grep -qE "Test Suite '.*' failed|with [1-9][0-9]* failures?" "$log"; then
        echo
        echo "port-gates: the Swift suite reported failures in its own output," \
             "whatever 'swift test' returned (exit $code). Failing the gate on that."
        code=1
    fi

    rm -f "$log"
    return $code
}

if [ -f "$CORPUS" ]; then
    echo "corpus: found — the conformance repository is above this one"
else
    echo "corpus: MISSING at $CORPUS"
    echo "        The session traces live in the parent repository. Check this repository out as"
    echo "        ISO15118ConformanceTests/libs/EVSimulatorApp, or the Kotlin and Swift gates will"
    echo "        fail on every trace test rather than on anything real."
fi

for gate in "${WANTED[@]}"; do
    case $gate in

    # --rerun-tasks: Gradle caches the `test` task and reports BUILD SUCCESSFUL without having run
    # anything, which is the same lie as a green summary over an aborted run.
    #
    # --continue: without it Gradle stops at the first failing module, so one red module hides
    # whatever the other nineteen would have said. Measured — the first run of this gate after the
    # wrapper landed failed in `v2g-certificates` and never reached the codec modules at all.
    kotlin)
        run kotlin bash -c 'cd kotlin && ./gradlew test --rerun-tasks --continue --console=plain' ;;

    swift)
        if command -v swift >/dev/null 2>&1
            then run swift swift_gate
            else skip swift "no Swift toolchain on this machine (the gate needs macOS or a Linux Swift install)"
        fi ;;

    # No dependencies at all, so no install step — `node --test` and the checked-in sources.
    typescript)
        run typescript bash -c 'cd typescript && npm test' ;;

    # The one package with a dependency (@capacitor/core, a devDependency): `npm ci` first, from the
    # checked-in lockfile. Without it the failure is ERR_MODULE_NOT_FOUND, which reads like a broken
    # import rather than a missing install.
    capacitor)
        run capacitor bash -c 'cd capacitor && npm ci --silent && npm test' ;;

    # `npm test` needs no install here either; `npm run build` does, and is not a gate.
    app)
        run app bash -c 'cd app && npm test' ;;

    *)
        echo "unknown gate '$gate' — known: ${ALL[*]}"; exit 2 ;;
    esac
done

echo; echo "===== summary ====="
failed=0
for gate in "${WANTED[@]}"; do
    printf '  %-12s %s\n' "$gate" "${RESULT[$gate]}"
    case "${RESULT[$gate]}" in FAILED*) failed=$((failed + 1));; esac
done

[ $failed -eq 0 ] && echo "  -> all gates that ran, passed" || echo "  -> $failed gate(s) failed"
exit $((failed > 0))

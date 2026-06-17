#!/usr/bin/env bash
# smoke-test-release.sh — logcat filter for R8-caused crashes during release smoke walk.
# Usage (logcat tail mode): ./scripts/smoke-test-release.sh
# Usage (precondition check): ./scripts/smoke-test-release.sh --check
#
# Run from repo root regardless of where invoked from.

set -euo pipefail

cd "$(dirname "$0")/.."

PKG="app.orbit"
PATTERN="ClassNotFoundException|NoSuchMethodException|NoSuchFieldException|VerifyError|AbstractMethodError|IncompatibleClassChangeError"

# ─── --check mode: assert release preconditions (RELEASE-06 + RELEASE-02) ───────────────
verify_release_preconditions() {
    local FAIL=0
    local MANIFEST="android/app/src/main/AndroidManifest.xml"

    echo "━━━ Release Precondition Check (RELEASE-06 + RELEASE-02) ━━━"

    # RELEASE-06: Justified permissions present
    if grep -q 'android.permission.READ_CALL_LOG' "$MANIFEST"; then
        echo "[PASS] RELEASE-06: READ_CALL_LOG present"
    else
        echo "[FAIL] RELEASE-06: READ_CALL_LOG missing from manifest"
        FAIL=1
    fi

    if grep -q 'android.permission.READ_CONTACTS' "$MANIFEST"; then
        echo "[PASS] RELEASE-06: READ_CONTACTS present"
    else
        echo "[FAIL] RELEASE-06: READ_CONTACTS missing from manifest"
        FAIL=1
    fi

    if grep -q 'android.permission.POST_NOTIFICATIONS' "$MANIFEST"; then
        echo "[PASS] RELEASE-06: POST_NOTIFICATIONS present"
    else
        echo "[FAIL] RELEASE-06: POST_NOTIFICATIONS missing from manifest"
        FAIL=1
    fi

    # RELEASE-06: Permission count is exactly 3
    PERM_COUNT=$(grep -c '<uses-permission' "$MANIFEST")
    if [ "$PERM_COUNT" = "3" ]; then
        echo "[PASS] RELEASE-06: permission count == 3 (no undeclared additions)"
    else
        echo "[FAIL] RELEASE-06: permission count == $PERM_COUNT (expected 3)"
        FAIL=1
    fi

    # RELEASE-06: No banned permissions
    if ! grep -qE 'android.permission.INTERNET|CALL_PHONE|WRITE_CONTACTS|USE_BIOMETRIC' "$MANIFEST"; then
        echo "[PASS] RELEASE-06: no banned permissions in manifest"
    else
        echo "[FAIL] RELEASE-06: banned permission found in manifest"
        FAIL=1
    fi

    # RELEASE-02: No un-gated dev affordances in main source
    if ! grep -rniE 'debug menu|dev menu|test button|TODO\(debug\)' android/app/src/main/java/ > /dev/null 2>&1; then
        echo "[PASS] RELEASE-02: no un-gated dev affordances in main source"
    else
        echo "[FAIL] RELEASE-02: un-gated dev affordance found in main source"
        FAIL=1
    fi

    echo
    if [ "$FAIL" = "0" ]; then
        echo "All preconditions PASS — manifest is final; source is clean."
    else
        echo "One or more preconditions FAILED — do not ship."
        exit 1
    fi
}

if [ "${1:-}" = "--check" ]; then
    verify_release_preconditions
    exit 0
fi

# ─── Default mode: tail logcat for R8 crashes during the on-device smoke walk ───────────
echo "Watching logcat for R8-related crashes on $PKG."
echo "Walk every screen in the app. Any match below = missing keep rule."
echo "Ctrl-C to stop."
echo

adb logcat -c
adb logcat -v time AndroidRuntime:E "$PKG":V '*:S' \
    | grep --line-buffered -E "$PATTERN" \
    || echo "No R8-related crashes detected."

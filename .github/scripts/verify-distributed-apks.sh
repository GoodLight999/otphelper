#!/usr/bin/env bash
set -euo pipefail

build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
apk_analyzer="$(command -v apkanalyzer || true)"
if [ -z "$apk_analyzer" ]; then
  apk_analyzer="$(find "$ANDROID_HOME/cmdline-tools" -type f -name apkanalyzer -perm -u+x | head -n 1)"
fi
if [ -z "$apk_analyzer" ] || [ ! -x "$apk_analyzer" ]; then
  echo "Unable to locate apkanalyzer in Android SDK" >&2
  exit 1
fi

mapfile -t apks < <(find app/build/outputs/apk -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} < 4 )); then
  echo "Expected normal/play debug/release APKs, found only ${#apks[@]}" >&2
  exit 1
fi

mkdir -p app/build/reports/apk-inspection
declare -A seen_variants=()
for apk in "${apks[@]}"; do
  case "$apk" in
    */normal/*) flavor="normal" ;;
    */play/*) flavor="play" ;;
    *)
      echo "Unable to identify APK flavor from path: $apk" >&2
      exit 1
      ;;
  esac
  case "$apk" in
    */debug/*) build_type="debug" ;;
    */release/*) build_type="release" ;;
    *)
      echo "Unable to identify APK build type from path: $apk" >&2
      exit 1
      ;;
  esac
  seen_variants["$flavor/$build_type"]=1

  name="$(basename "$apk")"
  unzip -tq "$apk"
  "$build_tools/apksigner" verify --verbose --print-certs "$apk" \
    > "app/build/reports/apk-inspection/${name}.signature.txt"
  "$apk_analyzer" manifest print "$apk" \
    > "app/build/reports/apk-inspection/${name}.manifest.xml"
  manifest="app/build/reports/apk-inspection/${name}.manifest.xml"
  grep -q 'io.github.jd1378.otphelper.PersistenceService' "$manifest"
  grep -q 'io.github.jd1378.otphelper.NotificationListener' "$manifest"
  grep -q 'io.github.jd1378.otphelper.AccessibilityNotificationService' "$manifest"
  grep -q 'rikka.shizuku.ShizukuProvider' "$manifest"
  grep -q 'android.permission.BIND_ACCESSIBILITY_SERVICE' "$manifest"
  grep -q 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' "$manifest"

  if grep -Eqi 'leakcanary|LeakLauncherActivity|LeakActivity' "$manifest"; then
    echo "LeakCanary components must not be shipped in $apk" >&2
    exit 1
  fi
  if grep -q 'io.github.jd1378.otphelper.fixture' "$manifest"; then
    echo "CI notification fixture leaked into distributed APK $apk" >&2
    exit 1
  fi

  python3 - "$manifest" "$flavor" "$build_type" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path, flavor, build_type = sys.argv[1:]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(manifest_path).getroot()
if root.get("package") != "io.github.jd1378.otphelper":
    raise SystemExit(f"Unexpected application package: {root.get('package')!r}")

version_name = root.get(android + "versionName") or ""
expected_suffix = "-magic" if flavor == "normal" else "-magic-play"
if not version_name.endswith(expected_suffix):
    raise SystemExit(
        f"{flavor}/{build_type} APK must expose the fork suffix "
        f"{expected_suffix!r}; actual versionName={version_name!r}"
    )

application = root.find("application")
if application is None:
    raise SystemExit("Merged APK Manifest has no application element")

is_debuggable = application.get(android + "debuggable") == "true"
if build_type == "debug" and not is_debuggable:
    raise SystemExit("Debug APK must remain debuggable for guarded signing migration")
if build_type == "release" and is_debuggable:
    raise SystemExit("Release APK must not be debuggable")

main_name = "io.github.jd1378.otphelper.MainActivity"
main = next(
    (
        activity
        for activity in application.findall("activity")
        if activity.get(android + "name") == main_name
    ),
    None,
)
if main is None:
    raise SystemExit("MainActivity is missing from merged APK Manifest")
if main.get(android + "exported") != "false":
    raise SystemExit("MainActivity must remain private (exported=false)")
if main.findall("intent-filter"):
    raise SystemExit("Private MainActivity must not expose Intent filters")
if main.get(android + "excludeFromRecents") == "true":
    raise SystemExit("MainActivity is excluded from Recents")

launcher_aliases = [
    alias
    for alias in application.findall("activity-alias")
    if alias.get(android + "targetActivity") == main_name
]
if len(launcher_aliases) != 1:
    raise SystemExit(
        "Exactly one launcher alias must target MainActivity; "
        f"found {len(launcher_aliases)}"
    )
launcher = launcher_aliases[0]
if launcher.get(android + "exported") != "true":
    raise SystemExit("MainActivity launcher alias must be exported")

launcher_filters = launcher.findall("intent-filter")
if len(launcher_filters) != 1:
    raise SystemExit("Launcher alias must expose exactly one MAIN/LAUNCHER intent filter")
launcher_filter = launcher_filters[0]
actions = {
    node.get(android + "name") for node in launcher_filter.findall("action")
}
categories = {
    node.get(android + "name") for node in launcher_filter.findall("category")
}
if actions != {"android.intent.action.MAIN"}:
    raise SystemExit(f"Launcher alias exposes unexpected actions: {sorted(actions)}")
if categories != {"android.intent.category.LAUNCHER"}:
    raise SystemExit(f"Launcher alias exposes unexpected categories: {sorted(categories)}")
if launcher_filter.findall("data"):
    raise SystemExit("Launcher alias must not expose URI/data matching")

# App-internal deep links use explicit intents/PendingIntents. No Activity may reintroduce the old
# externally browsable otphelper:// surface through a VIEW/BROWSABLE filter.
for component in list(application.findall("activity")) + list(application.findall("activity-alias")):
    for intent_filter in component.findall("intent-filter"):
        component_actions = {
            node.get(android + "name") for node in intent_filter.findall("action")
        }
        component_categories = {
            node.get(android + "name") for node in intent_filter.findall("category")
        }
        schemes = {
            node.get(android + "scheme")
            for node in intent_filter.findall("data")
            if node.get(android + "scheme")
        }
        if "otphelper" in schemes:
            raise SystemExit("External otphelper:// Activity surface must not be shipped")
        if (
            "android.intent.action.VIEW" in component_actions
            and "android.intent.category.BROWSABLE" in component_categories
        ):
            raise SystemExit(
                "Unexpected externally browsable Activity filter in distributed APK"
            )

internal_action = next(
    (
        activity
        for activity in application.findall("activity")
        if activity.get(android + "name") == "io.github.jd1378.otphelper.InternalActionActivity"
    ),
    None,
)
if internal_action is None:
    raise SystemExit("InternalActionActivity is missing")
if internal_action.get(android + "exported") != "false":
    raise SystemExit("InternalActionActivity must remain non-exported")
if internal_action.get(android + "excludeFromRecents") != "true":
    raise SystemExit("InternalActionActivity must be excluded from Recents")
if internal_action.get(android + "noHistory") != "true":
    raise SystemExit("InternalActionActivity must be noHistory")
if internal_action.findall("intent-filter"):
    raise SystemExit("InternalActionActivity must not expose Intent filters")

accessibility = next(
    (
        service
        for service in application.findall("service")
        if service.get(android + "name")
        == "io.github.jd1378.otphelper.AccessibilityNotificationService"
    ),
    None,
)
if accessibility is None:
    raise SystemExit("AccessibilityNotificationService is missing")
if accessibility.get(android + "exported") != "true":
    raise SystemExit("AccessibilityNotificationService must be exported for system binding")
if accessibility.get(android + "permission") != "android.permission.BIND_ACCESSIBILITY_SERVICE":
    raise SystemExit("AccessibilityNotificationService binding permission is incorrect")

shizuku = next(
    (
        provider
        for provider in application.findall("provider")
        if provider.get(android + "name") == "rikka.shizuku.ShizukuProvider"
    ),
    None,
)
if shizuku is None:
    raise SystemExit("Official ShizukuProvider is missing")
if shizuku.get(android + "exported") != "true":
    raise SystemExit("ShizukuProvider must be exported for Binder delivery")
if shizuku.get(android + "permission") != "android.permission.INTERACT_ACROSS_USERS_FULL":
    raise SystemExit("ShizukuProvider protection permission is incorrect")
if shizuku.get(android + "authorities") != "io.github.jd1378.otphelper.shizuku":
    raise SystemExit("ShizukuProvider authority is incorrect")

internal_permission_name = "io.github.jd1378.otphelper.permission.BROADCAST_CODE"
internal_permission = next(
    (
        permission
        for permission in root.findall("permission")
        if permission.get(android + "name") == internal_permission_name
    ),
    None,
)
if internal_permission is None:
    raise SystemExit("Internal notification-action permission is not declared")

protection_level = internal_permission.get(android + "protectionLevel")
try:
    protection_level_is_signature = int(protection_level or "", 0) == 0x2
except ValueError:
    protection_level_is_signature = protection_level == "signature"
if not protection_level_is_signature:
    raise SystemExit(
        "Internal notification-action permission must be signature-protected "
        f"(actual: {protection_level!r})"
    )

requested_permissions = {
    element.get(android + "name") for element in root.findall("uses-permission")
}
common_permissions = {
    internal_permission_name,
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.WAKE_LOCK",
    "io.github.jd1378.otphelper.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "moe.shizuku.manager.permission.API_V23",
}
normal_only_permissions = {
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.QUERY_ALL_PACKAGES",
}
expected_permissions = common_permissions | (
    normal_only_permissions if flavor == "normal" else set()
)
if requested_permissions != expected_permissions:
    missing = sorted(expected_permissions - requested_permissions)
    unexpected = sorted(requested_permissions - expected_permissions)
    raise SystemExit(
        f"Unexpected {flavor}/{build_type} permission set; "
        f"missing={missing}, unexpected={unexpected}"
    )

for forbidden_permission in (
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
):
    if forbidden_permission in requested_permissions:
        raise SystemExit(
            f"Offline or least-privilege APK contract violated by: {forbidden_permission}"
        )

action_receiver = next(
    (
        receiver
        for receiver in application.findall("receiver")
        if receiver.get(android + "name") == "io.github.jd1378.otphelper.NotifActionReceiver"
    ),
    None,
)
if action_receiver is None:
    raise SystemExit("NotifActionReceiver is missing")
if action_receiver.get(android + "exported") != "false":
    raise SystemExit("NotifActionReceiver must remain private (exported=false)")
if action_receiver.findall("intent-filter"):
    raise SystemExit("Private NotifActionReceiver must use explicit PendingIntents, not filters")
if action_receiver.get(android + "permission") != internal_permission_name:
    raise SystemExit("NotifActionReceiver is not protected by the signature permission")
PY
done

for required_variant in normal/debug normal/release play/debug play/release; do
  if [[ "${seen_variants[$required_variant]:-0}" != "1" ]]; then
    echo "Required APK variant was not inspected: $required_variant" >&2
    exit 1
  fi
done

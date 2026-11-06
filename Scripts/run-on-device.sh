#!/usr/bin/env bash
#
# Builds the test app (`app` module) with the local SDK, installs it on a
# device or emulator and launches it.
#
# Usage: Scripts/run-on-device.sh [-s <serial>] [-v <version>] [-r] [-l]
#   -s <serial>  device to use, skips the picker (same as ANDROID_SERIAL)
#   -v <version> Web client version overriding .crisp-client-version
#   -r           force regenerating the Web client assets and strings
#   -l           stream the app logs after launch
#
# Env: NPM_PULL_TOKEN (.npmrc contents, needed to generate the Web client),
#      ANDROID_SERIAL (target device, skips the picker),
#      CRISP_AVD (emulator to auto start when no device is connected)

set -euo pipefail

readonly APP_ID="im.crisp.catalog"
readonly MAIN_ACTIVITY=".MainComposeActivity"
readonly STRINGS_FILE="app/src/main/res/values/strings.xml"
readonly ASSETS_DIR="client/src/main/assets/crisp-client"
readonly CLIENT_STRINGS_FILE="client/src/main/res/values/strings.xml"
readonly EMULATOR_LOG="/tmp/crisp-sdk-emulator.log"

# Prints a message.
log() { echo "[run-on-device] $*"; }

# Prints an error message and exits.
fail() {
  echo "[run-on-device] error: $*" >&2
  exit 1
}

# Checks that a required command is installed.
require() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but not installed"
}

# Prints the serials of the connected devices and emulators, one per line.
connected_devices() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# Asks the user to pick one of the given items (first one when not interactive).
choose() {
  local prompt="$1"
  shift
  if [[ $# -eq 1 || ! -t 0 ]]; then
    echo "$1"
    return
  fi
  local item
  PS3="${prompt} "
  select item in "$@"; do
    [[ -n "${item}" ]] && { echo "${item}"; return; }
  done </dev/tty
}

# Finds the emulator binary (the PATH one is often the deprecated sdk/tools stub).
emulator_bin() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}}"
  if [[ -x "${sdk}/emulator/emulator" ]]; then
    echo "${sdk}/emulator/emulator"
  else
    command -v emulator || true
  fi
}

# Starts an AVD (CRISP_AVD, or picked from the list) and waits until it is booted.
start_emulator() {
  local emulator avds avd line
  emulator="$(emulator_bin)"
  [[ -n "${emulator}" ]] || fail "no device connected and the android emulator was not found (set ANDROID_HOME)"

  avds=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] && avds+=("${line}")
  done < <("${emulator}" -list-avds 2>/dev/null | grep -E '^[A-Za-z0-9_.-]+$')
  [[ ${#avds[@]} -gt 0 ]] || fail "no device connected and no AVD found (create one in Android Studio)"

  avd="${CRISP_AVD:-}"
  [[ -n "${avd}" ]] || avd="$(choose "Emulator to start:" "${avds[@]}")"

  # Own process group, so a Ctrl-C on the script never reaches the emulator.
  log "starting emulator ${avd} (it keeps running when the script stops)..."
  set -m
  nohup "${emulator}" -avd "${avd}" >"${EMULATOR_LOG}" 2>&1 </dev/null &
  disown
  set +m

  adb wait-for-device
  until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
}

# Makes sure exactly one target device is selected (ANDROID_SERIAL is honored by adb and Gradle).
ensure_device() {
  require adb
  adb start-server >/dev/null 2>&1 || true

  local devices=() line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && devices+=("${line}")
  done < <(connected_devices)

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf '%s\n' "${devices[@]:-}" | grep -qx "${ANDROID_SERIAL}" \
      || fail "ANDROID_SERIAL=${ANDROID_SERIAL} is not connected"
  elif [[ ${#devices[@]} -eq 0 ]]; then
    start_emulator
    ANDROID_SERIAL="$(connected_devices | head -n 1)"
  else
    ANDROID_SERIAL="$(choose "Device to use:" "${devices[@]}")"
  fi
  export ANDROID_SERIAL
  log "using device ${ANDROID_SERIAL}"
}

# Generates the Web client assets and strings when missing or forced.
generate_client() {
  local force="$1"
  local version="$2"
  if [[ "${force}" == "false" && -d "${ASSETS_DIR}" && -f "${CLIENT_STRINGS_FILE}" ]]; then
    return
  fi
  require mise
  log "generating the Web client and strings${version:+ (version ${version})}..."
  mise run fetch-crisp-client ${version:+"${version}"}
  mise run generate-strings ${version:+"${version}"}
}

# Fails if the Website ID of the test app is not filled in.
check_website_id() {
  if ! grep -Eq 'name="crisp_website_id">[^<]+<' "${STRINGS_FILE}"; then
    fail "crisp_website_id is empty in ${STRINGS_FILE}"
  fi
}

# Everything runs inside a function so bash parses the whole script upfront (safe to edit while running).
main() {
  local force_generate="false"
  local show_logs="false"
  local client_version=""
  local opt

  while getopts "s:v:rlh" opt; do
    case "${opt}" in
      s) ANDROID_SERIAL="${OPTARG}"; export ANDROID_SERIAL ;;
      v) client_version="${OPTARG}" ;;
      r) force_generate="true" ;;
      l) show_logs="true" ;;
      *) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
    esac
  done

  cd "$(dirname "$0")/.."

  ensure_device
  generate_client "${force_generate}" "${client_version}"
  check_website_id

  log "building and installing the debug app..."
  ./gradlew :app:installDebug

  adb shell am start -n "${APP_ID}/${MAIN_ACTIVITY}" >/dev/null
  log "app launched"

  if [[ "${show_logs}" == "true" ]]; then
    local pid
    pid="$(adb shell pidof "${APP_ID}" | tr -d '\r')"
    log "streaming logs, press Ctrl-C to stop"
    adb logcat ${pid:+--pid="${pid}"}
  fi
}

main "$@"

#!/usr/bin/env bash
# Shared helpers for turning the crisp-client web client into the files the
# CrispClient target needs.

# Utils method to exit with error and message
fail() {
  echo "$*" >&2
  exit 1
}

# Echoes the npm version to fetch: <requested> if given, otherwise the pin in .crisp-client-version. 
# Pins predating the npm switch are git tags (v4.6.25), so a leading v comes off either way.
resolve_crisp_client_version() {
  local requested=$1
  local pin_file="${PWD}/.crisp-client-version"

  if [[ -n "${requested}" ]]; then
    echo "${requested#v}"
    return
  fi

  if [[ ! -s "${pin_file}" ]]; then
    fail "No <version> given and ${pin_file} is missing or empty."
  fi

  local pin
  pin="$(tr -d '[:space:]' < "${pin_file}")"
  echo "${pin#v}"
}

# Writes the npm credentials to <dest_file> as an .npmrc. 
# NPM_PULL_TOKEN holds the .npmrc contents verbatim. 
# The token reaches npm through a file, never the command line or the repo.
write_npmrc() {
  local dest_file=$1

  if [[ -z "${NPM_PULL_TOKEN:-}" ]]; then
    fail "write_npmrc: NPM_PULL_TOKEN is not set (export the .npmrc contents)"
  fi

  # Created with 600 permissions before the token is written to it.
  (umask 077 && printf '%s\n' "${NPM_PULL_TOKEN}" > "${dest_file}")
  chmod 600 "${dest_file}"
  echo "Using npm credentials from NPM_PULL_TOKEN."
}

# Downloads and unpacks <version> of the CrispClient npm package.
# <version> is anything npm accepts: an exact version, a range, or a dist-tag such as 'latest'.
# Sets WEB_CLIENT_PKG_DIR (the unpacked package root) and
# WEB_CLIENT_VERSION (the version npm actually resolved) for the caller.
pull_web_client_package() {
  local version=$1

  # Deliberately not 'local': the EXIT trap runs after this function has returned,
  # so a local would be out of scope by then and the .npmrc would survive in /tmp.
  # This owns the EXIT trap. Extend it rather than adding a second one.
  WEB_CLIENT_TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "${WEB_CLIENT_TMP_DIR}"' EXIT

  write_npmrc "${WEB_CLIENT_TMP_DIR}/.npmrc"

  local spec="${CRISP_CLIENT_NPM_PACKAGE:?}@${version}"
  echo "Fetching ${spec}…"

  local tarball
  tarball="$(npm pack "${spec}" \
    --userconfig "${WEB_CLIENT_TMP_DIR}/.npmrc" \
    --pack-destination "${WEB_CLIENT_TMP_DIR}" \
    --silent --json | jq -r '.[0].filename')"

  if [[ -z "${tarball}" || ! -f "${WEB_CLIENT_TMP_DIR}/${tarball}" ]]; then
    fail "npm pack produced no tarball for ${spec}."
  fi

  # The tarball unpacks into a top-level 'package' directory.
  tar -xzf "${WEB_CLIENT_TMP_DIR}/${tarball}" -C "${WEB_CLIENT_TMP_DIR}"
  WEB_CLIENT_PKG_DIR="${WEB_CLIENT_TMP_DIR}/package"
  WEB_CLIENT_VERSION="$(jq -r .version "${WEB_CLIENT_PKG_DIR}/package.json")"
}

# Copies the published web client's dist/live into <dest_dist_dir>.
# The tarball ships the standalone build already,
# so unlike build_web_client_dist there is nothing to compile here.
# Sets WEB_CLIENT_VERSION for the caller.
fetch_web_client_dist() {
  local version=$1
  local dest_dist_dir=$2

  pull_web_client_package "${version}"

  if [[ ! -d "${WEB_CLIENT_PKG_DIR}/dist/live" ]]; then
    fail "${CRISP_CLIENT_NPM_PACKAGE}@${version} ships no dist/live - it was published without a standalone build."
  fi

  mkdir -p "${dest_dist_dir}"
  cp -R "${WEB_CLIENT_PKG_DIR}/dist/live/." "${dest_dist_dir}"
}

# Copies the published web client's per-locale JSON (src/locales) into <dest_locales_dir>.
# These are the translation sources the strings hierarchy is generated from.
# The built dist only carries them as hashed JS chunks.
# Sets WEB_CLIENT_VERSION for the caller.
fetch_web_client_locales() {
  local version=$1
  local dest_locales_dir=$2

  pull_web_client_package "${version}"

  if [[ ! -d "${WEB_CLIENT_PKG_DIR}/src/locales" ]]; then
    fail "${CRISP_CLIENT_NPM_PACKAGE}@${version} ships no src/locales - check the package's 'files' field."
  fi

  mkdir -p "${dest_locales_dir}"
  cp -R "${WEB_CLIENT_PKG_DIR}/src/locales/." "${dest_locales_dir}"
}

# Everything under the '_strings.sdk' group of crisp-client's per-locale JSON is ours,
# the rest belongs to the web client's own UI.
CRISP_STRINGS_GROUP="sdk"

# Regenerates the strings hierarchy from the per-locale JSON in <locales_dir>.
# The hierarchy is generated rather than hand-edited.
# crisp-client owns these translations and reships them on every release.
write_strings_hierarchy() {
  local locales_dir=$1
  local resources_dir=$2

  mkdir -p "$resources_dir"

  while IFS= read -r -d '' locale_file; do
    filename=$(basename "$locale_file")
    while IFS= read -r values_dir; do
      write_strings_file "$locale_file" "$values_dir" "$resources_dir"
    done < <(locale_directory "$filename")
  done < <(find "$locales_dir" -maxdepth 1 -type f -name '*.json' -print0)

  rm -rf "${locales_dir}"
}

# Returns the values folder name with correct qualifiers for locale filename.
locale_directory() {
  local filename=$1 stem lang script region
  # removes file extension
  stem=${filename%.json}

  case "$stem" in
    en) printf '%s\n' values ; return ;;
    he) printf '%s\n' values-iw ; return ;;
    id) printf '%s\n' values-in ; return ;;
    ckb) printf '%s\n' values-ku ; return ;;
    sr) printf '%s\n' values-sr values-b+sr+Latn ; return ;;
    sr-cyrl-cs) printf '%s\n' values-b+sr+Cyrl+ME values-b+sr+Cyrl+RS ; return ;;
  esac

  if [[ "$stem" =~ ^([a-z]{2}|ckb)-cyrl-([a-z]{2})$ ]]; then
    lang=${BASH_REMATCH[1]}
    region=${BASH_REMATCH[2]}
    region=$(printf '%s' "$region" | tr '[:lower:]' '[:upper:]')
    printf 'values-b+%s+Cyrl+%s\n' "$lang" "$region"
  elif [[ "$stem" =~ ^([a-z]{2}|ckb)-cyrl$ ]]; then
    lang=${BASH_REMATCH[1]}
    printf 'values-b+%s+Cyrl\n' "$lang"
  elif [[ "$stem" =~ ^([a-z]{2}|ckb)-([a-z]{2})$ ]]; then
    lang=${BASH_REMATCH[1]}
    region=${BASH_REMATCH[2]}
    region=$(printf '%s' "$region" | tr '[:lower:]' '[:upper:]')
    printf 'values-%s-r%s\n' "$lang" "$region"
  elif [[ "$stem" =~ ^([a-z]{2}|ckb)$ ]]; then
    printf 'values-%s\n' "$stem"
  else
    fail "unsupported locale filename: ${filename}"
  fi
}

# Writes the locale file content to the strings.xml file in its corresponding values directory.
write_strings_file() {
  local locale_file=$1 values_dir=$2 resources_dir=$3 target temp key value name escaped
  target="$resources_dir/$values_dir/strings.xml"
  mkdir -p "$resources_dir/$values_dir"
  jq -e '._strings.sdk | type == "object"' "$locale_file" >/dev/null \
    || fail "missing or malformed _strings.sdk in $(basename "$locale_file")"
  temp=$(mktemp "${target}.XXXXXX")

  {
    printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>'
    printf '%s\n' '<resources>'
    while IFS= read -r -d '' key && IFS= read -r -d '' value; do
      name="crisp_sdk_$key"
      if ! [[ "$name" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
        rm -f "$temp"
        fail "invalid Android resource name '$name' in $(basename "$locale_file")"
      fi
      escaped=$(xml_escape "$value")
      printf '    <string name="%s">%s</string>\n' "$name" "$escaped"
    done < <(jq -j '
      ._strings.sdk
      | if type != "object" then error("._strings.sdk must be an object") else . end
      | to_entries[]
      | select(.value | type == "string")
      | .key, "\u0000", .value, "\u0000"
    ' "$locale_file")
    printf '%s\n' '</resources>'
  } > "$temp"
  mv "$temp" "$target"
  printf 'Generated %s\n' "$values_dir"
}

# escape apostrophe (') character.
xml_escape() {
  local text=$1
  text=${text//\'/\\\'}
  printf '%s' "$text"
}

# Writes the CrispClient.kt glue (the CrispWebClient API) into <target_dir>.
# Relies on WEB_CLIENT_VERSION set by fetch_web_client_dist or build_web_client_dist.
write_crisp_client_android() {
  local target_dir=$1

  cat <<EOF > "${target_dir}/CrispClient.kt"
package im.crisp.client

import android.content.Context
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import java.io.IOException
import java.net.URL

internal object CrispClient {
    const val version: String = "${WEB_CLIENT_VERSION:?}"
    // Lowest WebView major version the crisp-client bundle renders correctly on
    // (its CSS needs Chrome 118: inset, logical float, color-mix(), :has()).
    // Lower bound from the CSS, verified end to end manually
    // Revisit it on each crisp-client bump.
    const val minWebViewMajor: Int = 118
    private const val ASSETS_PATH = "/assets/"
    const val CRISP_CLIENT_PATH = "crisp-client"

    class MissingAssetsError(cause: Throwable? = null): Exception(cause)

    fun assetsLoader(context: Context): WebViewAssetLoader {
        return WebViewAssetLoader.Builder()
            .addPathHandler(ASSETS_PATH, AssetsPathHandler(context))
            .build()
    }

    @Throws(MissingAssetsError::class)
    fun assetsURL(context: Context): URL {
        try {
            val files = context.getAssets().list(CRISP_CLIENT_PATH)
            if (files == null || files.size == 0) {
                throw MissingAssetsError()
            }

            return URL( "https://" + WebViewAssetLoader.DEFAULT_DOMAIN
                        + ASSETS_PATH + CRISP_CLIENT_PATH)
        }
        catch (e: IOException) {
            throw MissingAssetsError(e)
        }
    }

    fun generatedJS(): String {
        return """
import {
  botScenarioRun,
  chatShow,
  connectSocket,
  disconnectSocket,
  focusOnForeground,
  getMessages,
  getSessionData,
  getSessionIdentifier,
  getUserAvatar,
  getUserCompany,
  getUserEmail,
  getUserNickname,
  getUserPhone,
  helpdeskArticleOpen,
  helpdeskSearch,
  injectMessage,
  isSessionOngoing,
  messageShow,
  on,
  onInterfaceAction,
  onInterfaceLifecycle,
  registerNotification,
  reset,
  setSessionData,
  setSessionEvent,
  setSessionSegments,
  setUserAvatar,
  setUserCompany,
  setUserEmail,
  setUserNickname,
  setUserPhone,
  unregisterNotification,
} from './crisp-client.js'

window.CrispClient = {
  botScenarioRun,
  chatShow,
  connectSocket,
  disconnectSocket,
  focusOnForeground,
  getMessages,
  getSessionData,
  getSessionIdentifier,
  getUserAvatar,
  getUserCompany,
  getUserEmail,
  getUserNickname,
  getUserPhone,
  helpdeskArticleOpen,
  helpdeskSearch,
  injectMessage: globalThis.injectMessageEnvelope,
  isSessionOngoing,
  messageShow,
  registerNotification,
  reset,
  setSessionData,
  setSessionEvent,
  setSessionSegments,
  setUserAvatar,
  setUserCompany,
  setUserEmail,
  setUserNickname,
  setUserPhone,
  unregisterNotification,
}

onInterfaceAction((payload) => sendEvent('onInterfaceAction', payload))
onInterfaceLifecycle((payload) => sendEvent('onInterfaceLifecycle', payload))
on("message:received", (payload) => sendEvent("onMessageReceived", payload))
on("message:sent", (payload) => sendEvent("onMessageSent", payload))"""
    }
}
EOF
}
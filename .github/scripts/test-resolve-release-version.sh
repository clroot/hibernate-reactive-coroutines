#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
resolver="$script_dir/resolve-release-version.sh"

assert_output() {
  local expected=$1
  shift
  local actual
  actual=$(bash "$resolver" "$@")
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected '$expected' but got '$actual'" >&2
    exit 1
  fi
}

assert_failure() {
  if bash "$resolver" "$@" >/dev/null 2>&1; then
    echo "Expected version resolution to fail: $*" >&2
    exit 1
  fi
}

assert_output "1.2.3" release "1.2.3" "" tag "1.2.3"
assert_output "1.2.3" release "v1.2.3" "" tag "v1.2.3"
assert_output "2.0.0-RC1" workflow_dispatch "" "2.0.0-RC1" branch main
assert_output "1.4.0" workflow_dispatch "" "1.4.0" tag "v1.4.0"

assert_failure workflow_dispatch "" "" branch main
assert_failure release "not-a-version" "" tag "not-a-version"
assert_failure workflow_dispatch "" "1.2.3" tag "1.2.4"
assert_failure push "" "1.2.3" branch main

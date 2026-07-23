#!/usr/bin/env bash

set -euo pipefail

event_name=${1:-}
release_tag=${2:-}
manual_version=${3:-}
ref_type=${4:-}
ref_name=${5:-}

if [[ "$event_name" == "release" ]]; then
  candidate=$release_tag
elif [[ "$event_name" == "workflow_dispatch" ]]; then
  candidate=$manual_version
else
  echo "Unsupported release event: '$event_name'" >&2
  exit 1
fi

version=${candidate#v}
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
  echo "Invalid release version '$candidate'; expected a Maven-compatible semantic version" >&2
  exit 1
fi

if [[ "$ref_type" == "tag" ]]; then
  normalized_ref=${ref_name#v}
  if [[ "$normalized_ref" != "$version" ]]; then
    echo "Release version '$version' does not match checked-out tag '$ref_name'" >&2
    exit 1
  fi
fi

printf '%s\n' "$version"

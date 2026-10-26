#!/usr/bin/env bash
# Fetches the pinned upstream sources that cs-library is compiled from.
# CloudStream's `library` is the exact API that extensions are compiled against,
# so building it from source keeps binary compatibility with real .cs3/.jar plugins.
set -euo pipefail
cd "$(dirname "$0")/.."
CS_COMMIT="${CS_COMMIT:-a7ac454af8fbd64574c96847a936905855911720}"
NICEHTTP_TAG="${NICEHTTP_TAG:-0.4.18}"

fetch() { # url dir ref
  if [ ! -d "upstream/$2/.git" ]; then
    git init -q "upstream/$2"
    git -C "upstream/$2" remote add origin "$1"
  fi
  git -C "upstream/$2" fetch -q --depth 1 origin "$3"
  git -C "upstream/$2" checkout -q FETCH_HEAD
  echo "upstream/$2 @ $(git -C "upstream/$2" rev-parse --short HEAD)"
}
fetch https://github.com/recloudstream/cloudstream.git cloudstream "$CS_COMMIT"
fetch https://github.com/Blatzar/NiceHttp.git nicehttp "refs/tags/$NICEHTTP_TAG"

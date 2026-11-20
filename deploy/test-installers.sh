#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
for script in install.sh panel_install.sh deploy/rebuild.sh "$0"; do
  bash -n "$script"
done
python3 -I deploy/test-installer-fixtures.py

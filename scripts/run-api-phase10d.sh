#!/bin/bash
set -euo pipefail
ROOT="$HOME/Projects/StormOps"
SEC="$ROOT/.secrets/stormops-security.env"
[[ -f "$SEC" ]] || { echo 'Missing security credentials'; exit 1; }
[[ "$(stat -f %Lp "$SEC")" == "600" ]] || { echo 'Credentials permissions must be 600'; exit 1; }
set -a
source "$SEC"
set +a
exec /bin/bash "$ROOT/scripts/run-api-phase5.sh"

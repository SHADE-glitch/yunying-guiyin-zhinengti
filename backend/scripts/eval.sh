#!/usr/bin/env bash
# 评测批次 CLI。实现见 eval.py；保留本入口是为了文档和习惯统一。
# 例：bash backend/scripts/eval.sh agent --label baseline-42
#     bash backend/scripts/eval.sh compare <baseline-id> <candidate-id>
set -euo pipefail
exec python3 "$(dirname "$0")/eval.py" "$@"

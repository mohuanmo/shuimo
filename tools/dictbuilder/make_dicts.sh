#!/bin/bash
# 生成测试 fixtures + App 内置词典（§7.3 GKDT）
# 用法：在 tools/dictbuilder 目录执行 ./make_dicts.sh
set -e
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"

# 1) App 内置词典（assets）
python3 build_gkdt.py --lang zh --input seed/words_zh.tsv --out "$ROOT/app/src/main/assets/dict/gk_zh.gkdt"
python3 build_gkdt.py --lang en --input seed/words_en.tsv --out "$ROOT/app/src/main/assets/dict/gk_en.gkdt"

# 2) 单测 fixtures（小型确定性词库）
python3 build_gkdt.py --lang zh --input seed/test_zh.tsv --out "$ROOT/core/tests/fixtures/gk_test_zh.gkdt"
python3 build_gkdt.py --lang en --input seed/test_en.tsv --out "$ROOT/core/tests/fixtures/gk_test_en.gkdt"
echo "[make_dicts] 完成"

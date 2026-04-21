#!/usr/bin/env bash
# Pull Mozc OSS dictionary TSVs from upstream.
set -euo pipefail
cd "$(dirname "$0")"

base='https://raw.githubusercontent.com/google/mozc/master/src/data/dictionary_oss'
files=(
    dictionary00.txt dictionary01.txt dictionary02.txt dictionary03.txt
    dictionary04.txt dictionary05.txt dictionary06.txt dictionary07.txt
    dictionary08.txt dictionary09.txt
    suffix.txt
    README.txt
)
for f in "${files[@]}"; do
    if [[ -f "$f" ]] && [[ $(wc -c < "$f") -gt 100 ]]; then
        echo "skip $f (already present)"
        continue
    fi
    echo "fetch $f"
    curl -sSfL "$base/$f" -o "$f"
done

echo
echo "=== Sizes ==="
wc -l dictionary*.txt suffix.txt 2>/dev/null
echo
echo "=== Total ==="
du -sh .

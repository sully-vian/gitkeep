#!/usr/bin/env sh
set -euo pipefail

for file in fixtures/*.md; do
    filename="$(basename "$file")"
    content="$(cat "$file")"
    jq -n --arg name "$filename" --arg content "$content" '{($name): $content}'
done |
    jq -s 'add'

#!/usr/bin/env bash
# 按 weights/manifest.json 下载并校验模型权重。
#
#   ./scripts/download_weights.sh                          # 下载全部已填 url 的权重
#   ./scripts/download_weights.sh --proxy http://127.0.0.1:7892
#   ./scripts/download_weights.sh --only stff_ga.pth
#   ./scripts/download_weights.sh --check                   # 只体检，不下载
#
# 只下载 manifest 中 url 非空的条目；url 为空的会提示需要手动获取或自行训练。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEIGHTS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/weights"
MANIFEST="${WEIGHTS_DIR}/manifest.json"
PROXY=""
ONLY=""
CHECK_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --proxy) PROXY="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --check) CHECK_ONLY=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

CURL=(curl -L --fail --retry 3 --retry-delay 2 -C -)
[[ -n "${PROXY}" ]] && CURL+=(--proxy "${PROXY}")

PY="$(command -v python3 || true)"
[[ -z "${PY}" ]] && { echo "需要 python3 来解析 manifest.json" >&2; exit 1; }
[[ -f "${MANIFEST}" ]] || { echo "找不到 ${MANIFEST}" >&2; exit 1; }

sha256_of() {
  [[ -f "$1" ]] || { echo "-"; return; }
  shasum -a 256 "$1" | awk '{print substr($1,1,16)}'
}

while IFS=$'\t' read -r file url expected; do
  [[ -z "${file}" ]] && continue
  if [[ -n "${ONLY}" && "${file}" != "${ONLY}" ]]; then continue; fi

  target="${WEIGHTS_DIR}/${file}"
  printf '\n=== %s\n' "${file}"

  if [[ -n "${expected}" && -f "${target}" ]]; then
    actual="$(shasum -a 256 "${target}" | awk '{print $1}')"
    if [[ "${actual}" == "${expected}" ]]; then
      echo "  已存在且校验通过（$(du -h "${target}" | cut -f1)）"
      continue
    fi
    echo "  已存在但校验不通过，将重新下载"
  elif [[ -f "${target}" ]]; then
    echo "  已存在（$(du -h "${target}" | cut -f1)）；manifest 未填 sha256，跳过校验"
    continue
  fi

  if [[ -z "${url}" ]]; then
    echo "  ⚠ manifest 未填下载地址：需要手动获取或自行训练后放入本目录"
    continue
  fi
  if [[ "${CHECK_ONLY}" -eq 1 ]]; then
    echo "  缺失，可下载：${url}"
    continue
  fi

  echo "  下载中：${url}"
  "${CURL[@]}" -o "${target}.part" "${url}"
  if [[ -n "${expected}" ]]; then
    actual="$(shasum -a 256 "${target}.part" | awk '{print $1}')"
    [[ "${actual}" != "${expected}" ]] && { echo "  ✗ sha256 不匹配：期望 ${expected:0:16}… 实际 ${actual:0:16}…" >&2; rm -f "${target}.part"; exit 1; }
  fi
  mv "${target}.part" "${target}"
  echo "  ✓ 完成（$(du -h "${target}" | cut -f1)，sha256 ${expected:0:16}…）"
done < <("${PY}" - "${MANIFEST}" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding='utf-8'))
for item in data.get('weights', []):
    print('\t'.join([item.get('file', ''), item.get('url', '') or '', item.get('sha256', '') or '']))
PY
)

echo
echo "=== 权重目录现状（${WEIGHTS_DIR}）"
for f in "${WEIGHTS_DIR}"/*.pth; do
  [[ -e "${f}" ]] || { echo "  （暂无权重文件）"; break; }
  printf '  %-28s %-8s sha256 %s\n' "$(basename "${f}")" "$(du -h "${f}" | cut -f1)" "$(sha256_of "${f}")"
done
echo
echo "提示：权重放入后服务会自动检测并热加载；也可调用 POST /models/reload 立即生效。"

#!/usr/bin/env bash
# Calcula la siguiente versión (SemVer) a partir de los commits (Conventional Commits).
#
#   - Sin ningún tag v*              -> v1.0.0 (primera versión)
#   - "BREAKING CHANGE" o "tipo!:"   -> major  (v1.4.2 -> v2.0.0)
#   - "feat:"                        -> minor  (v1.4.2 -> v1.5.0)
#   - "fix:", "perf:", "refactor:"   -> patch  (v1.4.2 -> v1.4.3)
#   - solo docs/ci/chore/test/style  -> no hay versión nueva
#
# Compara contra el último tag publicado, así que los commits de una ejecución fallida
# se cuentan en la siguiente ejecución exitosa. Necesita el historial completo
# (actions/checkout con fetch-depth: 0).
#
# Salidas (en $GITHUB_OUTPUT si existe; si no, por pantalla):
#   anterior=v1.4.2  siguiente=v1.5.0  tipo=minor  publicar=true
set -euo pipefail

ref="${1:-HEAD}"

anterior="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' "$ref" 2>/dev/null || true)"

if [ -z "$anterior" ]; then
  anterior="ninguna"
  siguiente="v1.0.0"
  tipo="inicial"
else
  # Asunto y cuerpo de cada commit desde el último tag (incluye los títulos de los PR fusionados)
  mensajes="$(git log --format='%s%n%b' "${anterior}..${ref}")"

  if printf '%s\n' "$mensajes" | grep -Eiq '^[a-z]+(\([^)]*\))?!:|^BREAKING[ -]CHANGE:'; then
    tipo="major"
  elif printf '%s\n' "$mensajes" | grep -Eiq '^feat(\([^)]*\))?:'; then
    tipo="minor"
  elif printf '%s\n' "$mensajes" | grep -Eiq '^(fix|perf|refactor)(\([^)]*\))?:'; then
    tipo="patch"
  else
    tipo="ninguno"
  fi

  IFS=. read -r mayor menor parche <<< "${anterior#v}"
  case "$tipo" in
    major) siguiente="v$((mayor + 1)).0.0" ;;
    minor) siguiente="v${mayor}.$((menor + 1)).0" ;;
    patch) siguiente="v${mayor}.${menor}.$((parche + 1))" ;;
    *)     siguiente="$anterior" ;;
  esac
fi

if [ "$tipo" = "ninguno" ]; then publicar=false; else publicar=true; fi

salida="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "anterior=$anterior"
  echo "siguiente=$siguiente"
  echo "tipo=$tipo"
  echo "publicar=$publicar"
} >> "$salida"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Versión"
    echo "| Anterior | Siguiente | Tipo de cambio | ¿Se publica? |"
    echo "|---|---|---|---|"
    echo "| \`$anterior\` | \`$siguiente\` | $tipo | $publicar |"
  } >> "$GITHUB_STEP_SUMMARY"
fi

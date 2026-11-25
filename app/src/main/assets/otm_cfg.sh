CFG_F=0; CFG_NOCT=0; CV=""
cnorm() { printf '%s\n' "$1" | sed -E 's/(\.[0-9]*[1-9])0+$/\1/; s/\.0+$//'; }
cigual() { [ "$1" = "$2" ] && return 0; case "$1$2" in ''|*[!0-9.]*) return 1 ;; esac; [ "$(cnorm "$1")" = "$(cnorm "$2")" ]; }
cget() {      # ns chave -> $CV ("null" = nao existe)
  CV=$(settings get "$1" "$2" </dev/null 2>/dev/null); _cr=$?
  if [ "$_cr" -eq 0 ]; then
    case "$CV" in *"Failure calling"*|*"Failed transaction"*|*"Exception occurred"*|*"Can't find service"*) ;; *) CV=$(printf '%s\n' "$CV" | head -1); return 0 ;; esac
  fi
  CV=""
  [ "$CFG_NOCT" = 1 ] && return 1
  _co=$(content query --uri "content://settings/$1" --projection value --where "name='$2'" </dev/null 2>/dev/null)
  case "$_co" in
    *"No result found"*) CV=null; return 0 ;;
    "Row:"*) CV=$(printf '%s\n' "$_co" | sed -n 's/^Row: [0-9]* value=//p' | head -1); return 0 ;;
  esac
  CFG_NOCT=1; return 1
}
cput() {      # ns chave valor -> 0 so se ficou mesmo com o valor (confere lendo de volta)
  [ "$CFG_F" -ge 3 ] && return 1      # o servico caiu 3 vezes: nao insiste (poupa tempo)
  _ct=0
  while [ "$_ct" -lt 2 ]; do
    _po=$(settings put "$1" "$2" "$3" </dev/null 2>&1); _pr=$?
    _pf=0
    if [ "$_pr" -ne 0 ]; then _pf=1; else case "$_po" in *"Failure calling"*|*"Failed transaction"*|*"Exception occurred"*|*"Can't find service"*) _pf=1 ;; esac; fi
    if [ "$_pf" = 0 ]; then
      cget "$1" "$2" && cigual "$CV" "$3" && return 0
      return 1      # o servico aceitou e o valor nao ficou: o aparelho nao deixa mexer nessa chave
    fi
    if [ "$CFG_NOCT" != 1 ]; then
      content insert --uri "content://settings/$1" --bind "name:s:$2" --bind "value:s:$3" </dev/null >/dev/null 2>&1
      cget "$1" "$2" && cigual "$CV" "$3" && return 0
    fi
    _ct=$((_ct+1)); [ "$_ct" -lt 2 ] && sleep 1
  done
  CFG_F=$((CFG_F+1)); return 1
}
cdel() {      # ns chave -> 0 so se apagou (confere)
  settings delete "$1" "$2" </dev/null >/dev/null 2>&1
  cget "$1" "$2" && [ "$CV" = null ] && return 0
  [ "$CFG_NOCT" = 1 ] && return 1
  content delete --uri "content://settings/$1" --where "name='$2'" </dev/null >/dev/null 2>&1
  cget "$1" "$2" && [ "$CV" = null ]
}

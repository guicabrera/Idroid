#!/bin/zsh
# Idroid Bridge watchdog: keeps Obsidian (and with it the bridge) alive on this Mac.
# The plugin copies this file to ~/.idroid/obsidian-watchdog every time it loads.
#
#   obsidian-watchdog check      run by launchd every 2 min (see install)
#   obsidian-watchdog ensure     bridge answering, restarting Obsidian if not (used by ~/.idroid/copilot)
#   obsidian-watchdog restart    restart Obsidian now
#   obsidian-watchdog status     bridge, memory and idle time
#   obsidian-watchdog install | uninstall   the launchd agent
#
# Why: a long-running Obsidian renderer grows (2.5 GB at rest, more with Copilot,
# VS Code Pane, Excalidraw…) until Chromium's allocator gives up and the renderer
# dies (SIGTRAP). The main process stays up with no window, so the bridge is gone
# until someone restarts Obsidian. check restarts it after a crash or hang, and
# before that limit when memory goes over SOFT_MB while nobody is using it.
#
# Settings (optional) in ~/.idroid/watchdog.conf, zsh syntax:
SOFT_MB=5000      # restart above this footprint once Copilot and the Mac have been idle IDLE_MIN
HARD_MB=8000      # restart above this footprint even if in use: a crash is near anyway
IDLE_MIN=20
KEEP_OPEN=1       # start Obsidian if it isn't running
COOLDOWN=300      # seconds between restarts made by check
[[ -r ~/.idroid/watchdog.conf ]] && source ~/.idroid/watchdog.conf

zmodload zsh/datetime   # EPOCHSECONDS
DIR=~/.idroid
INFO=$DIR/copilot-bridge.json
STATE=$DIR/watchdog.state          # last copilot version, when it changed, failures, last restart
LOCK=$DIR/watchdog.lock
LOG=~/Library/Logs/Idroid/obsidian-watchdog.log
LABEL=com.idroid.obsidian-watchdog
PLIST=~/Library/LaunchAgents/$LABEL.plist

log() {
  mkdir -p ${LOG:h}
  [[ -f $LOG ]] && (( $(stat -f %z $LOG) > 524288 )) && mv -f $LOG $LOG.1
  print -r -- "$(date '+%F %T') $*" >> $LOG
}

typeset -A st
load_state() { [[ -r $STATE ]] && while IFS='=' read -r k v; do st[$k]=$v; done < $STATE; }
save_state() {
  local k
  for k in ${(k)st}; do print -r -- "$k=${st[$k]}"; done >| $STATE
}

info() { [[ -r $INFO ]] && /usr/bin/plutil -extract $1 raw -o - $INFO 2>/dev/null; }

main_pid() { pgrep -x Obsidian | head -1; }

# The renderer that runs the plugins: the pid the plugin wrote, else the first renderer.
renderer_pid() {
  local pid=$(info pid)
  [[ -n $pid ]] && kill -0 $pid 2>/dev/null && { print $pid; return; }
  pgrep -f 'Obsidian Helper \(Renderer\)' | head -1
}

footprint_mb() {
  local pid=$(renderer_pid)
  [[ -n $pid ]] || { print 0; return; }
  /usr/bin/footprint -p $pid 2>/dev/null | awk '/Footprint:/ { for (i = 1; i < NF; i++) if ($i == "Footprint:") { v = $(i+1); u = $(i+2) }; print int(u == "GB" ? v*1024 : (u == "KB" ? v/1024 : v)); exit }'
}

hid_idle_min() { ioreg -c IOHIDSystem | awk '/HIDIdleTime/ { print int($NF/1e9/60); exit }'; }

# Prints the bridge's /state (empty if it doesn't answer). Any HTTP answer means
# the server is up, even an error because Copilot isn't loaded yet.
bridge_state() {
  local port=$(info port) token=$(info token) pid=$(info pid)
  [[ -n $port && -n $token ]] || return 1
  [[ -n $pid ]] && ! kill -0 $pid 2>/dev/null && return 1
  local out
  out=$(/usr/bin/curl -s --max-time 10 -w '\n%{http_code}' -H "Authorization: Bearer $token" "http://127.0.0.1:$port/state?session=new") || return 1
  [[ ${out##*$'\n'} != 000 ]] || return 1
  print -r -- "${out%$'\n'*}"
}

wait_bridge() {
  local deadline=$(( SECONDS + $1 ))
  while (( SECONDS < deadline )); do
    bridge_state >/dev/null && return 0
    sleep 2
  done
  return 1
}

# Only one restart at a time; a lock older than 3 minutes is from a run that died.
take_lock() {
  mkdir $LOCK 2>/dev/null && return 0
  [[ -n $(find $LOCK -maxdepth 0 -mmin +3 2>/dev/null) ]] && rm -rf $LOCK && mkdir $LOCK 2>/dev/null
}

restart() {
  take_lock || { log "restart skipped ($1): another one is running"; return 1; }
  log "restart: $1"
  if [[ -n $(main_pid) ]]; then
    # Quit first (Obsidian saves as it goes); force it if it hangs, e.g. with a dead renderer.
    osascript -e 'tell application "Obsidian" to quit' >/dev/null 2>&1 &
    local i
    for i in {1..15}; do [[ -z $(main_pid) ]] && break; sleep 1; done
    [[ -n $(main_pid) ]] && { pkill -x Obsidian; sleep 3; }
    [[ -n $(main_pid) ]] && { pkill -9 -x Obsidian; sleep 1; }
  fi
  rm -f $INFO   # stale: the next plugin load writes a new port
  open -g -a Obsidian
  load_state; st[restarted]=$EPOCHSECONDS; st[fails]=0; st[changed]=$EPOCHSECONDS; save_state
  local ok=0
  wait_bridge 90 && ok=1
  rm -rf $LOCK
  (( ok )) && log "restart: bridge back on port $(info port)" || log "restart: bridge didn't come back in 90s"
  (( ok ))
}

cmd_ensure() {
  bridge_state >/dev/null && return 0
  # Another restart in progress (watchdog or a parallel call): wait for it.
  if [[ -d $LOCK ]]; then wait_bridge 100 && return 0; fi
  print -u2 "Obsidian isn't answering on this Mac; restarting it…"
  restart "bridge not answering (on demand)" && return 0
  print -u2 "Obsidian didn't come back on this Mac: open it and check that Idroid Bridge is enabled."
  return 3
}

cmd_check() {
  load_state
  local now=$EPOCHSECONDS
  local main=$(main_pid)
  if [[ -z $main ]]; then
    (( KEEP_OPEN )) && restart "Obsidian not running"
    return
  fi
  local recent=$(( now - ${st[restarted]:-0} < COOLDOWN ))
  local up=$(ps -o etime= -p $main | awk -F'[-:]' '{ s=0; for (i=1;i<=NF;i++) s=s*60+$i; if (NF==4) s=$1*86400+$2*3600+$3*60+$4; print s }')
  local state
  if ! state=$(bridge_state); then
    # Starting up (or a plugin reload): give it two checks before acting.
    (( up < 120 )) && return
    st[fails]=$(( ${st[fails]:-0} + 1 )); save_state
    (( ${st[fails]} >= 2 && ! recent )) && restart "bridge not answering for ${st[fails]} checks (renderer crashed or hung)"
    return
  fi
  local version=$(print -r -- "$state" | /usr/bin/plutil -extract version raw -o - - 2>/dev/null)
  if [[ $version != ${st[version]} ]]; then st[version]=$version; st[changed]=$now; fi
  st[fails]=0; save_state
  (( recent )) && return
  local mb=$(footprint_mb)
  if (( mb > HARD_MB )); then
    restart "renderer at ${mb} MB (hard limit ${HARD_MB})"
  elif (( mb > SOFT_MB )); then
    local copilot_idle=$(( (now - ${st[changed]:-$now}) / 60 )) hid=$(hid_idle_min)
    if (( copilot_idle >= IDLE_MIN && hid >= IDLE_MIN )); then
      restart "renderer at ${mb} MB (soft limit ${SOFT_MB}), idle ${copilot_idle}m"
    fi
  fi
}

cmd_status() {
  load_state
  local state; state=$(bridge_state) && print "bridge: answering on port $(info port)" || print "bridge: NOT answering"
  print "obsidian pid: $(main_pid), renderer pid: $(renderer_pid), footprint: $(footprint_mb) MB (soft $SOFT_MB, hard $HARD_MB)"
  print "copilot idle: $(( (EPOCHSECONDS - ${st[changed]:-$EPOCHSECONDS}) / 60 ))m, Mac idle: $(hid_idle_min)m"
  print "last restart: ${st[restarted]:+$(date -r ${st[restarted]} '+%F %T')}"
  launchctl print gui/$UID/$LABEL >/dev/null 2>&1 && print "launchd agent: loaded" || print "launchd agent: not installed"
  if [[ -f $LOG ]]; then print -r -- "--- log"; tail -5 $LOG; fi
}

cmd_install() {
  mkdir -p ${PLIST:h} ${LOG:h}
  cat >| $PLIST <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$DIR/obsidian-watchdog</string><string>check</string></array>
  <key>StartInterval</key><integer>120</integer>
  <key>StandardErrorPath</key><string>$LOG</string>
  <key>ProcessType</key><string>Background</string>
</dict>
</plist>
EOF
  launchctl bootout gui/$UID/$LABEL 2>/dev/null
  launchctl bootstrap gui/$UID $PLIST && print "installed: $LABEL (every 2 min, log $LOG)"
}

cmd_uninstall() {
  launchctl bootout gui/$UID/$LABEL 2>/dev/null
  rm -f $PLIST && print "removed: $LABEL"
}

case $1 in
  check) cmd_check ;;
  ensure) cmd_ensure ;;
  restart) restart "manual" ;;
  status) cmd_status ;;
  install) cmd_install ;;
  uninstall) cmd_uninstall ;;
  *) print -u2 "usage: ${0:t} check|ensure|restart|status|install|uninstall"; exit 2 ;;
esac

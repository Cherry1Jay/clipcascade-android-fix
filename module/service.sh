#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 8
setprop service.adb.tcp.port 5555
if ! (netstat -tln 2>/dev/null | grep -q ":5555"); then
  stop adbd
  sleep 2
  start adbd
fi

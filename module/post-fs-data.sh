#!/system/bin/sh
# 开机早期写入端口属性：adbd 启动时即监听 5555（persist 属性有被清理的可能，这里兜底）
setprop service.adb.tcp.port 5555

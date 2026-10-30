#!/system/bin/sh
for f in $(find /system/lib64 /system/lib /system/bin /system/framework -name "*.so" -o -name "*.jar" 2>/dev/null); do
  if strings "$f" 2>/dev/null | grep -qi "keylayout"; then
    echo "== $f"
    strings "$f" 2>/dev/null | grep -i "keylayout" | sort -u | head -5
  fi
done

#!/system/bin/sh
# Android compatibility runner. This accepts the small bwrap profile emitted
# by DSH and maps it to PRoot bind arguments. It is intentionally opt-in.
PROOT=/data/adb/dsh/proot
ARGS="-0 -r /"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ro-bind|--bind)
      [ "$#" -ge 3 ] || exit 125
      ARGS="$ARGS -b $2:$3"
      shift 3
      ;;
    --dev|--proc|--die-with-parent)
      shift
      ;;
    --tmpfs)
      [ "$#" -ge 2 ] || exit 125
      shift 2
      ;;
    --)
      shift
      exec "$PROOT" $ARGS "$@"
      ;;
    *)
      shift
      ;;
  esac
done
exit 125

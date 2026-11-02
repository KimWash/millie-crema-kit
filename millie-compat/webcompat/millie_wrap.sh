# Invoked by Zygote WrapperInit via build.prop wrap.kr.co.millie.eink="sh /system/etc/webview_compat/millie_wrap.sh"
# argv: /system/bin/app_process /system/bin --application ... WrapperInit <fd> <sdk> android.app.ActivityThread
# - preload libcompat_statvfs.so (statvfs for Android 4.2 bionic; needed by libc++_shared.so / PDF)
# - extra Dalvik VM options for this app only: /data/local/tmp/millie_vmopts (dev override) or millie_vmopts here
# - preload libcompat_webtls.so (lets WebViewClassic's chromium net use TLS 1.2 of system OpenSSL 1.0.1c)
# - preload libcompat_props.so: per-app system property overrides (COMPAT_PROPS), used for Dalvik heap tuning
#   (AndroidRuntime re-adds dalvik.vm.heap* from properties after command-line VM options, so they must be
#   overridden at the property level). Values: /data/local/tmp/millie_props (dev) or millie_props here.
export LD_PRELOAD=/system/lib/libcompat_statvfs.so:/system/lib/libcompat_webtls.so:/system/lib/libcompat_props.so
if [ -r /data/local/tmp/millie_props ]; then export COMPAT_PROPS=$(cat /data/local/tmp/millie_props)
elif [ -r /system/etc/webview_compat/millie_props ]; then export COMPAT_PROPS=$(cat /system/etc/webview_compat/millie_props); fi
shift
VMOPTS=""
if [ -r /data/local/tmp/millie_vmopts ]; then VMOPTS=$(cat /data/local/tmp/millie_vmopts)
elif [ -r /system/etc/webview_compat/millie_vmopts ]; then VMOPTS=$(cat /system/etc/webview_compat/millie_vmopts); fi
exec /system/bin/app_process $VMOPTS "$@"

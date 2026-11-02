/*
 * libcompat_webtls.so — LD_PRELOAD shim for Android 4.2 WebViewClassic (libchromium_net.so).
 *
 * The 2012 Chromium OpenSSL socket forces SSL_OP_NO_TLSv1_1 | SSL_OP_NO_TLSv1_2 via
 * SSL_set_options() (= SSL_ctrl(ssl, SSL_CTRL_OPTIONS, ...)) although the system OpenSSL 1.0.1c
 * supports TLS 1.2 + AES-GCM. Servers that require TLS 1.2 (e.g. AWS ALB TLS-1-2 policies) then
 * fail the handshake inside WebView. This shim clears exactly those two bits, and only when the
 * caller is libchromium_net.so; every other caller (Java/conscrypt, native libs) is passed through
 * unchanged. It does not touch certificate verification.
 */
#include <dlfcn.h>
#include <string.h>
#include <android/log.h>

#define SSL_CTRL_OPTIONS        32
#define SSL_OP_NO_TLSv1_2       0x08000000L
#define SSL_OP_NO_TLSv1_1       0x10000000L
#define TAG "WebTLS"

typedef long (*ctrl_fn)(void *, int, long, void *);
static ctrl_fn real_ssl_ctrl, real_ctx_ctrl;
static int logged;

static void *libssl(void) {
    static void *h;
    if (!h) h = dlopen("libssl.so", RTLD_NOW);
    return h;
}

static int from_chromium(void *ret) {
    Dl_info info;
    if (!ret || !dladdr(ret, &info) || !info.dli_fname) return 0;
    return strstr(info.dli_fname, "libchromium_net") != NULL;
}

static long fix(int cmd, long larg, void *ret) {
    if (cmd == SSL_CTRL_OPTIONS && (larg & (SSL_OP_NO_TLSv1_1 | SSL_OP_NO_TLSv1_2)) && from_chromium(ret)) {
        if (!logged) { logged = 1; __android_log_print(ANDROID_LOG_INFO, TAG, "allowing TLS 1.1/1.2 for WebView (options 0x%lx)", larg); }
        larg &= ~(SSL_OP_NO_TLSv1_1 | SSL_OP_NO_TLSv1_2);
    }
    return larg;
}

long SSL_ctrl(void *s, int cmd, long larg, void *parg) {
    if (!real_ssl_ctrl) real_ssl_ctrl = (ctrl_fn) dlsym(libssl(), "SSL_ctrl");
    return real_ssl_ctrl(s, cmd, fix(cmd, larg, __builtin_return_address(0)), parg);
}

long SSL_CTX_ctrl(void *ctx, int cmd, long larg, void *parg) {
    if (!real_ctx_ctrl) real_ctx_ctrl = (ctrl_fn) dlsym(libssl(), "SSL_CTX_ctrl");
    return real_ctx_ctrl(ctx, cmd, fix(cmd, larg, __builtin_return_address(0)), parg);
}

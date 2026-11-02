/*
 * libcompat_props.so — LD_PRELOAD shim: per-process overrides of system properties read through
 * libcutils property_get(). Overrides come from the env var COMPAT_PROPS="key=value;key=value".
 *
 * Use: give one wrapped app (wrap.<pkg>) its own Dalvik heap tuning. AndroidRuntime::startVm reads
 * dalvik.vm.heap* with property_get() and appends them *after* any VM options passed to app_process,
 * so -XX:HeapMaxFree etc. on the command line are overridden; overriding the property values is the
 * only way to change them for this process without touching the global (zygote) settings.
 */
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#define PROP_VALUE_MAX 92
typedef int (*pget_fn)(const char *, char *, const char *);

static const char *lookup(const char *key, size_t *len) {
    const char *s = getenv("COMPAT_PROPS");
    size_t kl = strlen(key);
    while (s && *s) {
        const char *end = strchr(s, ';');
        size_t n = end ? (size_t)(end - s) : strlen(s);
        if (n > kl && strncmp(s, key, kl) == 0 && s[kl] == '=') {
            *len = n - kl - 1;
            return s + kl + 1;
        }
        s = end ? end + 1 : NULL;
    }
    return NULL;
}

int property_get(const char *key, char *value, const char *default_value) {
    static pget_fn real;
    size_t n;
    const char *v = key ? lookup(key, &n) : NULL;
    if (v) {
        if (n >= PROP_VALUE_MAX) n = PROP_VALUE_MAX - 1;
        memcpy(value, v, n);
        value[n] = 0;
        return (int) n;
    }
    if (!real) {
        void *h = dlopen("libcutils.so", RTLD_NOW);
        if (h) real = (pget_fn) dlsym(h, "property_get");
    }
    if (real) return real(key, value, default_value);
    if (default_value) { strncpy(value, default_value, PROP_VALUE_MAX - 1); value[PROP_VALUE_MAX - 1] = 0; return (int) strlen(value); }
    value[0] = 0;
    return 0;
}

#include <stdio.h>
#include <dlfcn.h>
struct sv { unsigned long bsize, frsize, blocks, bfree, bavail, files, ffree, favail, fsid, flag, namemax; };
int main(int argc, char **argv) {
    const char *lib = argc > 1 ? argv[1] : "/data/app-lib/kr.co.millie.eink-1/libc++_shared.so";
    void *h = dlopen(lib, RTLD_NOW);
    printf("dlopen(%s) = %p %s\n", lib, h, h ? "" : dlerror());
    int (*f)(const char *, struct sv *) = (int (*)(const char *, struct sv *))dlsym(RTLD_DEFAULT, "statvfs");
    if (!f) { void *s = dlopen("libcompat_statvfs.so", RTLD_NOW); f = s ? dlsym(s, "statvfs") : 0; }
    const char *paths[] = {"/data", "/sdcard", "/system"};
    for (int i = 0; f && i < 3; i++) {
        struct sv v; int r = f(paths[i], &v);
        printf("statvfs(%s)=%d bsize=%lu frsize=%lu blocks=%lu bfree=%lu bavail=%lu files=%lu flag=0x%lx namemax=%lu => total %luK avail %luK\n",
               paths[i], r, v.bsize, v.frsize, v.blocks, v.bfree, v.bavail, v.files, v.flag, v.namemax,
               v.blocks * (v.frsize / 1024), v.bavail * (v.frsize / 1024));
    }
    return 0;
}

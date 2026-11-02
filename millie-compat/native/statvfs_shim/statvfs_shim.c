/*
 * libcompat_statvfs.so — statvfs()/fstatvfs() for Android 4.2 bionic (added to bionic in API 19).
 * Preloaded (LD_PRELOAD via the wrap.<pkg> property) into apps whose NDK libs reference statvfs,
 * e.g. Millie's libc++_shared.so. Self-contained: raw statfs64/fstatfs64 syscalls, only needs
 * syscall() from libc (present in 4.2).
 */
#include <stdint.h>

long syscall(long number, ...);

#define NR_statfs64  266  /* ARM EABI */
#define NR_fstatfs64 267
#define ST_VALID     0x0020

/* Kernel struct statfs64 on ARM EABI (packed, 4-byte aligned => 84 bytes). */
struct k_statfs64 {
    uint32_t f_type;
    uint32_t f_bsize;
    uint64_t f_blocks;
    uint64_t f_bfree;
    uint64_t f_bavail;
    uint64_t f_files;
    uint64_t f_ffree;
    int32_t  f_fsid[2];
    uint32_t f_namelen;
    uint32_t f_frsize;
    uint32_t f_flags;
    uint32_t f_spare[4];
} __attribute__((packed, aligned(4)));

/* bionic struct statvfs, 32-bit layout (fsblkcnt_t/fsfilcnt_t = unsigned long). */
struct statvfs {
    unsigned long f_bsize;
    unsigned long f_frsize;
    unsigned long f_blocks;
    unsigned long f_bfree;
    unsigned long f_bavail;
    unsigned long f_files;
    unsigned long f_ffree;
    unsigned long f_favail;
    unsigned long f_fsid;
    unsigned long f_flag;
    unsigned long f_namemax;
};

static unsigned long clamp(uint64_t v) { return v > 0xffffffffULL ? 0xffffffffUL : (unsigned long)v; }

static void convert(const struct k_statfs64 *s, struct statvfs *d) {
    d->f_bsize   = s->f_bsize;
    d->f_frsize  = s->f_frsize ? s->f_frsize : s->f_bsize;
    d->f_blocks  = clamp(s->f_blocks);
    d->f_bfree   = clamp(s->f_bfree);
    d->f_bavail  = clamp(s->f_bavail);
    d->f_files   = clamp(s->f_files);
    d->f_ffree   = clamp(s->f_ffree);
    d->f_favail  = clamp(s->f_ffree);
    d->f_fsid    = (unsigned long)s->f_fsid[0];
    d->f_flag    = (s->f_flags & ST_VALID) ? (s->f_flags & ~ST_VALID) : 0;
    d->f_namemax = s->f_namelen;
}

__attribute__((visibility("default")))
int statvfs(const char *path, struct statvfs *out) {
    struct k_statfs64 s;
    if (syscall(NR_statfs64, path, (long)sizeof(s), &s) != 0) return -1; /* errno set by syscall() */
    convert(&s, out);
    return 0;
}

__attribute__((visibility("default")))
int fstatvfs(int fd, struct statvfs *out) {
    struct k_statfs64 s;
    if (syscall(NR_fstatfs64, (long)fd, (long)sizeof(s), &s) != 0) return -1;
    convert(&s, out);
    return 0;
}

/* LP32 bionic: statvfs64 == statvfs */
__attribute__((visibility("default"), alias("statvfs")))
int statvfs64(const char *path, struct statvfs *out);
__attribute__((visibility("default"), alias("fstatvfs")))
int fstatvfs64(int fd, struct statvfs *out);

_Static_assert(sizeof(struct k_statfs64) == 84, "kernel statfs64 size");

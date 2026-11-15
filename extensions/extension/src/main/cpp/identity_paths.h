#ifndef WEIBO_IDENTITY_PATHS_H
#define WEIBO_IDENTITY_PATHS_H
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

static inline bool weibo_should_redirect(bool active, const char *path, const char *installed) {
    return active && path && installed && strcmp(path, installed) == 0;
}

static inline bool weibo_should_rewrite_link(bool active, const char *bytes, size_t count,
                                             const char *original) {
    return active && bytes && original && count == strlen(original) &&
           memcmp(bytes, original, count) == 0;
}
#endif

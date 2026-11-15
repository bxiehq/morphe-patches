#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "identity_paths.h"

/* The target's bundled ShadowHook provides these APIs. No target binary is redistributed. */
static int (*hook_init)(int mode, bool debug);
static void *(*hook_symbol)(const char *, const char *, void *, void **);
static int (*unhook_symbol)(void *);
static void *(*previous_function)(void *);
static void (*pop_stack)(void *);
static _Thread_local bool verification_active;
static char original_path[PATH_MAX];
static char installed_path[PATH_MAX];
static bool ready;

static const char *metadata_path(const char *path) {
    return weibo_should_redirect(verification_active, path, installed_path) ? original_path : path;
}

static int identity_open(const char *path, int flags) {
    int (*previous)(const char *, int) = previous_function((void *) identity_open);
    int result = previous(metadata_path(path), flags);
    pop_stack(__builtin_return_address(0));
    return result;
}

static FILE *identity_fopen(const char *path, const char *mode) {
    FILE *(*previous)(const char *, const char *) = previous_function((void *) identity_fopen);
    FILE *result = previous(metadata_path(path), mode);
    pop_stack(__builtin_return_address(0));
    return result;
}

static ssize_t identity_readlink(const char *path, char *buffer, size_t capacity) {
    ssize_t (*previous)(const char *, char *, size_t) = previous_function((void *) identity_readlink);
    ssize_t result = previous(path, buffer, capacity);
    if (result >= 0 && weibo_should_rewrite_link(verification_active, buffer, (size_t) result, original_path)) {
        size_t length = strlen(installed_path);
        if (length > capacity) length = capacity;
        memcpy(buffer, installed_path, length);
        result = (ssize_t) length;
    }
    pop_stack(__builtin_return_address(0));
    return result;
}

JNIEXPORT jboolean JNICALL Java_app_bxiehq_weibo_Identity_configure(
        JNIEnv *env, jclass type, jstring original, jstring installed) {
    (void) type;
    if (ready) return JNI_TRUE;
    if (!original || !installed) return JNI_FALSE;
    const char *source = (*env)->GetStringUTFChars(env, original, NULL);
    if (!source) return JNI_FALSE;
    const char *target = (*env)->GetStringUTFChars(env, installed, NULL);
    if (!target) {
        (*env)->ReleaseStringUTFChars(env, original, source);
        return JNI_FALSE;
    }
    bool valid = strlen(source) < sizeof(original_path) && strlen(target) < sizeof(installed_path);
    if (valid) {
        strcpy(original_path, source);
        strcpy(installed_path, target);
    }
    (*env)->ReleaseStringUTFChars(env, original, source);
    (*env)->ReleaseStringUTFChars(env, installed, target);
    if (!valid) return JNI_FALSE;

    // Loading through dlopen deliberately does not call ShadowHook's Java JNI_OnLoad.
    void *library = dlopen("libshadowhook.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) return JNI_FALSE;
    hook_init = (int (*)(int, bool)) dlsym(library, "shadowhook_init");
    hook_symbol = (void *(*)(const char *, const char *, void *, void **)) dlsym(library, "shadowhook_hook_sym_name");
    unhook_symbol = (int (*)(void *)) dlsym(library, "shadowhook_unhook");
    previous_function = (void *(*)(void *)) dlsym(library, "shadowhook_get_prev_func");
    pop_stack = (void (*)(void *)) dlsym(library, "shadowhook_pop_stack");
    if (!hook_init || !hook_symbol || !unhook_symbol || !previous_function || !pop_stack) return JNI_FALSE;
    if (hook_init(0 /* SHADOWHOOK_MODE_SHARED */, false) != 0) return JNI_FALSE;
    void *open_hook = hook_symbol("libc.so", "__open_2", (void *) identity_open, NULL);
    void *fopen_hook = hook_symbol("libc.so", "fopen", (void *) identity_fopen, NULL);
    void *readlink_hook = hook_symbol("libc.so", "readlink", (void *) identity_readlink, NULL);
    ready = open_hook && fopen_hook && readlink_hook;
    if (!ready) {
        if (open_hook) unhook_symbol(open_hook);
        if (fopen_hook) unhook_symbol(fopen_hook);
        if (readlink_hook) unhook_symbol(readlink_hook);
    }
    __android_log_print(ANDROID_LOG_INFO, "WeiboIdentity", "Native identity adapter ready: %s", ready ? "true" : "false");
    return ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_app_bxiehq_weibo_Identity_setActive(JNIEnv *env, jclass type, jboolean active) {
    (void) env;
    (void) type;
    verification_active = ready && active;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}

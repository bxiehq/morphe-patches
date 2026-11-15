#include <assert.h>
#include <stdio.h>
#include "../../extensions/extension/src/main/cpp/identity_paths.h"

int main(void) {
    const char *installed = "/data/app/com.sina.weibo/base.apk";
    const char *original = "/data/data/com.sina.weibo/files/weibo-original-8150.apk";
    assert(weibo_should_redirect(true, installed, installed));
    assert(!weibo_should_redirect(false, installed, installed));
    assert(!weibo_should_redirect(true, "/data/app/another.app/base.apk", installed));
    assert(!weibo_should_redirect(true, "/data/app/com.sina.weibo/base.apk.extra", installed));
    assert(!weibo_should_redirect(true, NULL, installed));
    assert(weibo_should_rewrite_link(true, original, strlen(original), original));
    assert(!weibo_should_rewrite_link(false, original, strlen(original), original));
    assert(!weibo_should_rewrite_link(true, original, strlen(original) - 1, original));
    assert(!weibo_should_rewrite_link(true, "/other/app/files/weibo-original-8150.apk", 39, original));
    const char unterminated[] = {'a', 'b', 'c'};
    assert(weibo_should_rewrite_link(true, unterminated, sizeof(unterminated), "abc"));
    assert(!weibo_should_rewrite_link(true, unterminated, 2, "abc"));
    puts("Identity paths: unrelated reads and inactive verification remain unchanged.");
    return 0;
}

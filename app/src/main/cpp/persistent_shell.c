#define _GNU_SOURCE
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "qadb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static pid_t g_child    = -1;
static int   g_read_fd  = -1;
static int   g_write_fd = -1;

extern char **environ;

static char *kv(const char *k, const char *v)
{
    size_t kl = strlen(k), vl = strlen(v);
    char *s = (char *) malloc(kl + vl + 2);
    if (!s) return NULL;
    memcpy(s, k, kl);
    s[kl] = '=';
    memcpy(s + kl + 1, v, vl);
    s[kl + 1 + vl] = '\0';
    return s;
}

static int overridden(const char *entry, size_t namelen)
{
    static const char *const keys[] = { "PATH", "HOME", "TMPDIR", "TERM", NULL };
    for (int i = 0; keys[i]; ++i) {
        size_t kl = strlen(keys[i]);
        if (kl == namelen && strncmp(entry, keys[i], namelen) == 0) return 1;
    }
    return 0;
}

static char **build_env(const char *path, const char *home, const char *tmp)
{
    size_t n = 0;
    while (environ[n]) n++;

    char **env = (char **) calloc(n + 6, sizeof(char *));
    if (!env) return NULL;

    size_t j = 0;
    for (size_t i = 0; i < n; i++) {
        char *e  = environ[i];
        char *eq = strchr(e, '=');
        if (eq && overridden(e, (size_t)(eq - e))) continue;
        env[j++] = e;
    }
    env[j++] = kv("PATH", path);
    env[j++] = kv("HOME", home);
    env[j++] = kv("TMPDIR", tmp);
    env[j++] = kv("TERM", "dumb");
    env[j++] = kv("ANDROID_ADB_SERVER_PORT", "5037");
    env[j]   = NULL;
    return env;
}

static void throw_io(JNIEnv *env, const char *what)
{
    char msg[256];
    snprintf(msg, sizeof(msg), "%s failed: %s", what, strerror(errno));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static jintArray make_int3(JNIEnv *env, jint a, jint b, jint c)
{
    jintArray arr = (*env)->NewIntArray(env, 3);
    if (!arr) return NULL;
    jint v[3] = { a, b, c };
    (*env)->SetIntArrayRegion(env, arr, 0, 3, v);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_quest_adbterm_NativeShell_start(JNIEnv *env, jobject thiz,
                                         jstring jAdbDir, jstring jHomeDir, jstring jTmpDir)
{
    signal(SIGPIPE, SIG_IGN);

    if (g_child > 0 && g_read_fd >= 0 && g_write_fd >= 0) {
        return make_int3(env, g_read_fd, g_write_fd, (jint) g_child);
    }

    const char *adb_dir = (*env)->GetStringUTFChars(env, jAdbDir, NULL);
    const char *home    = (*env)->GetStringUTFChars(env, jHomeDir, NULL);
    const char *tmp     = (*env)->GetStringUTFChars(env, jTmpDir, NULL);
    if (!adb_dir || !home || !tmp) {
        jclass iae = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (iae) (*env)->ThrowNew(env, iae, "null path");
        return NULL;
    }

    size_t plen = strlen(adb_dir) + strlen(":/system/bin:/system/xbin") + 1;
    char  *path = (char *) malloc(plen);
    if (path) snprintf(path, plen, "%s:/system/bin:/system/xbin", adb_dir);

    char **envp = build_env(path ? path : "/system/bin", home, tmp);

    int in_pipe[2]  = { -1, -1 };
    int out_pipe[2] = { -1, -1 };

    if (!path || !envp)                  { throw_io(env, "malloc");        goto fail; }
    if (pipe2(in_pipe,  O_CLOEXEC) != 0) { throw_io(env, "pipe2(stdin)");  goto fail; }
    if (pipe2(out_pipe, O_CLOEXEC) != 0) { throw_io(env, "pipe2(stdout)"); goto fail; }

    pid_t pid = fork();
    if (pid < 0) { throw_io(env, "fork"); goto fail; }

    if (pid == 0) {
        setsid();

        if (dup2(in_pipe[0],  STDIN_FILENO)  < 0) _exit(126);
        if (dup2(out_pipe[1], STDOUT_FILENO) < 0) _exit(126);
        if (dup2(out_pipe[1], STDERR_FILENO) < 0) _exit(126);

        if (in_pipe[0]  > STDERR_FILENO) close(in_pipe[0]);
        if (out_pipe[1] > STDERR_FILENO) close(out_pipe[1]);

        signal(SIGPIPE, SIG_DFL);
        if (home) chdir(home);

        char *argv[] = { (char *) "sh", NULL };
        execve("/system/bin/sh", argv, envp);
        _exit(127);
    }

    close(in_pipe[0]);
    close(out_pipe[1]);
    g_read_fd  = out_pipe[0];
    g_write_fd = in_pipe[1];
    g_child    = pid;

    LOGI("spawned /system/bin/sh pid=%d read_fd=%d write_fd=%d",
         (int) pid, g_read_fd, g_write_fd);

    free(path);
    free(envp);
    (*env)->ReleaseStringUTFChars(env, jAdbDir, adb_dir);
    (*env)->ReleaseStringUTFChars(env, jHomeDir, home);
    (*env)->ReleaseStringUTFChars(env, jTmpDir, tmp);

    return make_int3(env, g_read_fd, g_write_fd, (jint) pid);

fail:
    if (in_pipe[0]  >= 0) close(in_pipe[0]);
    if (in_pipe[1]  >= 0) close(in_pipe[1]);
    if (out_pipe[0] >= 0) close(out_pipe[0]);
    if (out_pipe[1] >= 0) close(out_pipe[1]);
    free(path);
    free(envp);
    (*env)->ReleaseStringUTFChars(env, jAdbDir, adb_dir);
    (*env)->ReleaseStringUTFChars(env, jHomeDir, home);
    (*env)->ReleaseStringUTFChars(env, jTmpDir, tmp);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_quest_adbterm_NativeShell_write(JNIEnv *env, jobject thiz, jbyteArray data)
{
    if (g_write_fd < 0 || !data) return -1;

    jsize  len   = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;

    size_t  off = 0;
    ssize_t n;
    while (off < (size_t) len) {
        n = write(g_write_fd, bytes + off, (size_t) len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("write() failed: %s", strerror(errno));
            break;
        }
        off += (size_t) n;
    }

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint) off;
}

JNIEXPORT void JNICALL
Java_com_quest_adbterm_NativeShell_stop(JNIEnv *env, jobject thiz)
{
    if (g_write_fd >= 0) { close(g_write_fd); g_write_fd = -1; }

    if (g_child > 0) {
        kill(-g_child, SIGKILL);
        kill(g_child,  SIGKILL);
        int status;
        for (int i = 0; i < 20; i++) {
            if (waitpid(g_child, &status, WNOHANG) == g_child) break;
            usleep(10 * 1000);
        }
        g_child = -1;
    }

    if (g_read_fd >= 0) { close(g_read_fd); g_read_fd = -1; }
    LOGI("persistent shell stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_quest_adbterm_NativeShell_isAlive(JNIEnv *env, jobject thiz)
{
    if (g_child <= 0) return JNI_FALSE;
    int status = 0;
    if (waitpid(g_child, &status, WNOHANG) == g_child) { g_child = -1; return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_quest_adbterm_NativeShell_chmod(JNIEnv *env, jobject thiz, jstring jpath, jint mode)
{
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!p) return -1;
    int rc = chmod(p, (mode_t) mode);
    int e  = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    errno = e;
    return rc;
}

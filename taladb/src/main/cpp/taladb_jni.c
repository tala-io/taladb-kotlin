/*
 * JNI shim between dev.taladb.Native and the TalaDB C FFI (taladb.h).
 *
 * Deliberately thin: most operations go through taladb_call(), which takes an
 * operation name and a JSON argument array, so adding an operation to the
 * Kotlin API rarely touches this file. Only calls that carry a float vector
 * have their own entry points, so the vector crosses as a float array rather
 * than as JSON text — plus the three live-query calls, which hold a handle of
 * their own.
 *
 * Strings cross as byte arrays, never as jstring
 * ----------------------------------------------
 * JNI's GetStringUTFChars/NewStringUTF speak *modified* UTF-8: U+0000 becomes
 * two bytes and every character outside the BMP — every emoji — becomes a
 * six-byte surrogate pair. The engine requires standard UTF-8 and rejects
 * both. So the Kotlin side encodes with String.encodeToByteArray() plus a
 * trailing NUL, and decodes results with ByteArray.decodeToString().
 *
 * Errors
 * ------
 * taladb_last_error() and taladb_last_error_code() are thread-local and
 * describe the most recent call on the calling thread, so both are read here,
 * in the same JNI call that failed, and rethrown as dev.taladb.TalaDBException
 * (message, code). Reading them from Kotlin later could observe a different
 * thread's slot.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "taladb.h"

static jclass g_tala_exception; /* dev/taladb/TalaDBException, global ref */
static jclass g_string;
static jmethodID g_exception_ctor;
static jmethodID g_string_utf8_ctor;
static jstring g_utf8_charset;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "dev/taladb/TalaDBException");
    if (local == NULL) return JNI_ERR;
    g_tala_exception = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (g_tala_exception == NULL) return JNI_ERR;
    g_exception_ctor = (*env)->GetMethodID(env, g_tala_exception, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (g_exception_ctor == NULL) return JNI_ERR;
    local = (*env)->FindClass(env, "java/lang/String");
    if (local == NULL) return JNI_ERR;
    g_string = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (g_string == NULL) return JNI_ERR;
    g_string_utf8_ctor = (*env)->GetMethodID(env, g_string, "<init>", "([BLjava/lang/String;)V");
    if (g_string_utf8_ctor == NULL) return JNI_ERR;
    jstring charset = (*env)->NewStringUTF(env, "UTF-8"); /* ASCII is valid modified UTF-8. */
    if (charset == NULL) return JNI_ERR;
    g_utf8_charset = (*env)->NewGlobalRef(env, charset);
    (*env)->DeleteLocalRef(env, charset);
    return g_utf8_charset == NULL ? JNI_ERR : JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return;
    (*env)->DeleteGlobalRef(env, g_tala_exception);
    (*env)->DeleteGlobalRef(env, g_string);
    (*env)->DeleteGlobalRef(env, g_utf8_charset);
}

/* ------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* ------------------------------------------------------------------------- */

static void throw_by_name(JNIEnv *env, const char *cls, const char *msg) {
    jclass c = (*env)->FindClass(env, cls);
    if (c != NULL) (*env)->ThrowNew(env, c, msg);
}

/* Throw TalaDBException carrying the engine's message and stable error code
 * for the call that just failed on this thread, or `fallback` and no code if
 * it left none. */
static void throw_last_error(JNIEnv *env, const char *fallback) {
    const char *msg = taladb_last_error();
    const char *code_utf8 = taladb_last_error_code(); /* static; never freed */
    if (msg == NULL) msg = fallback;
    /* ThrowNew expects modified UTF-8, whereas the engine returns standard
     * UTF-8. Decode a byte array instead so supplementary characters survive. */
    size_t n = strlen(msg);
    if (n > INT32_MAX) {
        throw_by_name(env, "java/lang/OutOfMemoryError", "error message exceeds the maximum Java array size");
        return;
    }
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)n);
    if (bytes == NULL) return;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)n, (const jbyte *)msg);
    if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, bytes); return; }
    jobject message = (*env)->NewObject(env, g_string, g_string_utf8_ctor, bytes, g_utf8_charset);
    (*env)->DeleteLocalRef(env, bytes);
    if (message == NULL) return;
    /* Codes are ASCII identifiers ("Encryption", "InvalidFilter"), which are
     * valid modified UTF-8, so NewStringUTF is safe for them. */
    jstring code = NULL;
    if (code_utf8 != NULL) {
        code = (*env)->NewStringUTF(env, code_utf8);
        if (code == NULL) { (*env)->DeleteLocalRef(env, message); return; }
    }
    jobject exception = (*env)->NewObject(env, g_tala_exception, g_exception_ctor, message, code);
    (*env)->DeleteLocalRef(env, message);
    if (code != NULL) (*env)->DeleteLocalRef(env, code);
    if (exception == NULL) return;
    (*env)->Throw(env, exception);
    (*env)->DeleteLocalRef(env, exception);
}

/* A NUL-terminated UTF-8 string borrowed from a Kotlin ByteArray. */
typedef struct {
    jbyteArray array;
    jbyte *bytes; /* NULL when the Kotlin argument was null */
} Utf8;

/* Borrow `array`. Returns 0 on success, or -1 with a Java exception pending.
 * A null array yields a NULL pointer, which the FFI reads as "absent" for its
 * optional arguments and rejects with a message for required ones. */
static int utf8_get(JNIEnv *env, jbyteArray array, Utf8 *out) {
    out->array = array;
    out->bytes = NULL;
    if (array == NULL) return 0;
    jsize len = (*env)->GetArrayLength(env, array);
    if (len == 0) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "string argument is not NUL-terminated");
        return -1;
    }
    /* Not GetPrimitiveArrayCritical: these calls can run for as long as a
     * full collection scan, and a critical region would stall the GC for all
     * of it. Elements are released with JNI_ABORT — nothing is written back. */
    out->bytes = (*env)->GetByteArrayElements(env, array, NULL);
    if (out->bytes == NULL) return -1; /* OutOfMemoryError pending */
    if (out->bytes[len - 1] != 0) {
        (*env)->ReleaseByteArrayElements(env, array, out->bytes, JNI_ABORT);
        out->bytes = NULL;
        throw_by_name(env, "java/lang/IllegalArgumentException", "string argument is not NUL-terminated");
        return -1;
    }
    return 0;
}

static void utf8_release(JNIEnv *env, Utf8 *s) {
    if (s->bytes != NULL) (*env)->ReleaseByteArrayElements(env, s->array, s->bytes, JNI_ABORT);
    s->bytes = NULL;
}

static const char *utf8_ptr(const Utf8 *s) { return (const char *)s->bytes; }

/* Copy an engine-owned result string into a new ByteArray and free it. A NULL
 * result is the engine reporting an error: throw it. */
static jbyteArray take_result(JNIEnv *env, char *result, const char *fallback) {
    if (result == NULL) {
        throw_last_error(env, fallback);
        return NULL;
    }
    size_t n = 0;
    while (result[n] != '\0') n++;
    if (n > INT32_MAX) {
        taladb_free_string(result);
        throw_by_name(env, "java/lang/OutOfMemoryError", "result exceeds the maximum Java array size");
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)result);
    taladb_free_string(result);
    return out; /* NULL with OutOfMemoryError pending if allocation failed */
}

static TalaDbHandle *handle_of(jlong h) { return (TalaDbHandle *)(intptr_t)h; }

/* ------------------------------------------------------------------------- */
/* ABI                                                                        */
/* ------------------------------------------------------------------------- */

/* The ABI version of the header this shim was compiled against. Compared with
 * libraryAbiVersion() before anything else runs: a signature that changed
 * between the two still links, and then corrupts the stack. */
JNIEXPORT jint JNICALL Java_dev_taladb_Native_headerAbiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (jint)TALADB_FFI_ABI_VERSION;
}

JNIEXPORT jint JNICALL Java_dev_taladb_Native_libraryAbiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (jint)taladb_ffi_abi_version();
}

/* ------------------------------------------------------------------------- */
/* Lifecycle                                                                  */
/* ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_dev_taladb_Native_open(
    JNIEnv *env, jobject self, jbyteArray path, jbyteArray config) {
    (void)self;
    Utf8 p, c;
    if (utf8_get(env, path, &p) != 0) return 0;
    if (utf8_get(env, config, &c) != 0) { utf8_release(env, &p); return 0; }
    TalaDbHandle *h = c.bytes != NULL
        ? taladb_open_with_config(utf8_ptr(&p), utf8_ptr(&c))
        : taladb_open(utf8_ptr(&p));
    if (h == NULL) throw_last_error(env, "failed to open database");
    utf8_release(env, &c);
    utf8_release(env, &p);
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL Java_dev_taladb_Native_close(JNIEnv *env, jobject self, jlong h) {
    (void)env; (void)self;
    taladb_close(handle_of(h));
}

/* ------------------------------------------------------------------------- */
/* Operations                                                                 */
/* ------------------------------------------------------------------------- */

/* Run `op` with a JSON argument array; returns the JSON result. */
JNIEXPORT jbyteArray JNICALL Java_dev_taladb_Native_call(
    JNIEnv *env, jobject self, jlong h, jbyteArray op, jbyteArray args) {
    (void)self;
    Utf8 o, a;
    if (utf8_get(env, op, &o) != 0) return NULL;
    if (utf8_get(env, args, &a) != 0) { utf8_release(env, &o); return NULL; }
    char *result = taladb_call(handle_of(h), utf8_ptr(&o), utf8_ptr(&a));
    /* Read the error before releasing: release is a JNI call, not a taladb_*
     * call, so it cannot clear the slot — but keeping the read adjacent to the
     * failing call is what makes that obviously true. */
    jbyteArray out = take_result(env, result, "operation failed");
    utf8_release(env, &a);
    utf8_release(env, &o);
    return out;
}

/* Borrow a FloatArray for the duration of one FFI call. */
typedef struct {
    jfloatArray array;
    jfloat *values;
    jsize len;
} Floats;

static int floats_get(JNIEnv *env, jfloatArray array, Floats *out) {
    out->array = array;
    out->len = (*env)->GetArrayLength(env, array);
    out->values = (*env)->GetFloatArrayElements(env, array, NULL);
    return out->values == NULL ? -1 : 0;
}

static void floats_release(JNIEnv *env, Floats *f) {
    if (f->values != NULL) (*env)->ReleaseFloatArrayElements(env, f->array, f->values, JNI_ABORT);
    f->values = NULL;
}

JNIEXPORT jbyteArray JNICALL Java_dev_taladb_Native_findNearest(
    JNIEnv *env, jobject self, jlong h, jbyteArray collection, jbyteArray field,
    jfloatArray query, jint topK, jbyteArray filter) {
    (void)self;
    if (topK < 0) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "topK must not be negative");
        return NULL;
    }
    Utf8 col, fld, flt;
    Floats q;
    jbyteArray out = NULL;
    if (utf8_get(env, collection, &col) != 0) return NULL;
    if (utf8_get(env, field, &fld) != 0) goto release_col;
    if (utf8_get(env, filter, &flt) != 0) goto release_fld;
    if (floats_get(env, query, &q) != 0) goto release_flt;

    out = take_result(env,
        taladb_find_nearest(handle_of(h), utf8_ptr(&col), utf8_ptr(&fld),
                            q.values, (uintptr_t)q.len, (uintptr_t)topK, utf8_ptr(&flt)),
        "findNearest failed");

    floats_release(env, &q);
release_flt:
    utf8_release(env, &flt);
release_fld:
    utf8_release(env, &fld);
release_col:
    utf8_release(env, &col);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_dev_taladb_Native_hybridSearch(
    JNIEnv *env, jobject self, jlong h, jbyteArray collection, jbyteArray textField,
    jbyteArray text, jbyteArray vectorField, jfloatArray vector, jint topK,
    jbyteArray filter, jbyteArray options) {
    (void)self;
    if (topK < 0) {
        throw_by_name(env, "java/lang/IllegalArgumentException", "topK must not be negative");
        return NULL;
    }
    Utf8 col, tf, tx, vf, flt, opt;
    Floats v;
    jbyteArray out = NULL;
    if (utf8_get(env, collection, &col) != 0) return NULL;
    if (utf8_get(env, textField, &tf) != 0) goto release_col;
    if (utf8_get(env, text, &tx) != 0) goto release_tf;
    if (utf8_get(env, vectorField, &vf) != 0) goto release_tx;
    if (utf8_get(env, filter, &flt) != 0) goto release_vf;
    if (utf8_get(env, options, &opt) != 0) goto release_flt;
    if (floats_get(env, vector, &v) != 0) goto release_opt;

    out = take_result(env,
        taladb_hybrid_search(handle_of(h), utf8_ptr(&col), utf8_ptr(&tf), utf8_ptr(&tx),
                             utf8_ptr(&vf), v.values, (uintptr_t)v.len, (uintptr_t)topK,
                             utf8_ptr(&flt), utf8_ptr(&opt)),
        "hybridSearch failed");

    floats_release(env, &v);
release_opt:
    utf8_release(env, &opt);
release_flt:
    utf8_release(env, &flt);
release_vf:
    utf8_release(env, &vf);
release_tx:
    utf8_release(env, &tx);
release_tf:
    utf8_release(env, &tf);
release_col:
    utf8_release(env, &col);
    return out;
}

/* ------------------------------------------------------------------------- */
/* Live queries                                                               */
/* ------------------------------------------------------------------------- */

/* Returns a non-zero watch handle. `filter` may be null for all documents. */
JNIEXPORT jlong JNICALL Java_dev_taladb_Native_watchOpen(
    JNIEnv *env, jobject self, jlong h, jbyteArray collection, jbyteArray filter) {
    (void)self;
    Utf8 col, flt;
    if (utf8_get(env, collection, &col) != 0) return 0;
    if (utf8_get(env, filter, &flt) != 0) { utf8_release(env, &col); return 0; }
    TalaDbWatch *w = taladb_watch(handle_of(h), utf8_ptr(&col), utf8_ptr(&flt));
    if (w == NULL) throw_last_error(env, "failed to open live query");
    utf8_release(env, &flt);
    utf8_release(env, &col);
    return (jlong)(intptr_t)w;
}

/* The next snapshot as a JSON array, or null if `timeoutMs` passed without a
 * write. */
JNIEXPORT jbyteArray JNICALL Java_dev_taladb_Native_watchNext(
    JNIEnv *env, jobject self, jlong w, jint timeoutMs) {
    (void)self;
    char *json = NULL;
    int32_t rc = taladb_watch_next((TalaDbWatch *)(intptr_t)w,
                                   timeoutMs < 0 ? 0u : (uint32_t)timeoutMs, &json);
    if (rc == 0) return NULL;
    if (rc < 0) {
        throw_last_error(env, "live query failed");
        return NULL;
    }
    return take_result(env, json, "live query failed");
}

JNIEXPORT void JNICALL Java_dev_taladb_Native_watchClose(JNIEnv *env, jobject self, jlong w) {
    (void)env; (void)self;
    taladb_watch_close((TalaDbWatch *)(intptr_t)w);
}

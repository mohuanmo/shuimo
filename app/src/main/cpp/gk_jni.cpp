// GlassKey JNI 桥（§8.1 C ABI → JNIEnv）
// 仅做类型转换与生命周期管理，业务逻辑全部在 glasskey core（C++17）
#include <jni.h>
#include <cstring>
#include <string>

#include "engine.h"
#include "glasskey/gk_api.h"

namespace {

jstring ToJString(JNIEnv* env, const char* s) {
  if (s == nullptr) return env->NewStringUTF("");
  return env->NewStringUTF(s);
}

gk::ImeEngine* Handle(jlong h) { return reinterpret_cast<gk::ImeEngine*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeVersion(JNIEnv* env, jobject) {
  return ToJString(env, ime_version());
}

JNIEXPORT jlong JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeCreate(JNIEnv* env, jobject, jstring dictPath,
                                                   jstring latinPath, jint fuzzyMask,
                                                   jint beamWidth, jint maxCandidates,
                                                   jboolean enableLearning) {
  if (dictPath == nullptr) return 0;
  const char* dict = env->GetStringUTFChars(dictPath, nullptr);
  const char* latin = latinPath != nullptr ? env->GetStringUTFChars(latinPath, nullptr) : nullptr;
  gk::EngineConfig cfg{};
  cfg.dict_path = dict;
  cfg.latin_path = latin;
  cfg.fuzzy_mask = static_cast<uint32_t>(fuzzyMask);
  cfg.beam_width = beamWidth;
  cfg.max_candidates = maxCandidates;
  cfg.enable_learning = enableLearning ? 1 : 0;
  ime_h h = ime_engine_create(&cfg);
  env->ReleaseStringUTFChars(dictPath, dict);
  if (latin != nullptr) env->ReleaseStringUTFChars(latinPath, latin);
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeDestroy(JNIEnv*, jobject, jlong h) {
  ime_engine_destroy(Handle(h));
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeSetLayout(JNIEnv*, jobject, jlong h, jint layout) {
  ime_set_layout(Handle(h), layout);
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeSetFuzzy(JNIEnv*, jobject, jlong h, jint mask) {
  ime_set_fuzzy(Handle(h), static_cast<uint32_t>(mask));
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeSetLearning(JNIEnv*, jobject, jlong h, jboolean on) {
  ime_set_learning(Handle(h), on ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeProcessKey(JNIEnv*, jobject, jlong h, jint code,
                                                       jint action, jint meta) {
  return ime_process_key(Handle(h), static_cast<uint32_t>(code), action, meta);
}

JNIEXPORT jint JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeBackspace(JNIEnv*, jobject, jlong h) {
  return ime_backspace(Handle(h));
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeReset(JNIEnv*, jobject, jlong h) {
  ime_reset(Handle(h));
}

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeGetInput(JNIEnv* env, jobject, jlong h) {
  char buf[64];
  ime_get_input(Handle(h), buf, sizeof(buf));
  return ToJString(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeGetSegmentedInput(JNIEnv* env, jobject, jlong h) {
  char buf[160];  // 自动分词串最多原串(64)+空格数，冗余一倍防截断
  if (ime_get_segmented_input(Handle(h), buf, sizeof(buf)) != gk::IME_OK) buf[0] = '\0';
  return ToJString(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeInsertManualSeparator(JNIEnv*, jobject, jlong h) {
  return ime_insert_manual_separator(Handle(h));
}

JNIEXPORT jint JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeCandidateCount(JNIEnv*, jobject, jlong h) {
  return ime_candidate_count(Handle(h));
}

/// 设置上文（v1.3 上下文通道）：光标前已上屏文本，供候选重排
JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeSetContext(JNIEnv* env, jobject, jlong h,
                                                       jstring before) {
  if (before == nullptr) {
    ime_set_context(Handle(h), nullptr, 0);
    return;
  }
  const char* s = env->GetStringUTFChars(before, nullptr);
  int len = s != nullptr ? static_cast<int>(strlen(s)) : 0;
  ime_set_context(Handle(h), s, len);
  if (s != nullptr) env->ReleaseStringUTFChars(before, s);
}

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeGetCandidate(JNIEnv* env, jobject, jlong h, jint idx) {
  char buf[128];
  if (ime_get_candidate(Handle(h), idx, buf, sizeof(buf)) != gk::IME_OK) buf[0] = '\0';
  return ToJString(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeGetCandidatePinyin(JNIEnv* env, jobject, jlong h,
                                                               jint idx) {
  char buf[96];
  if (ime_get_candidate_pinyin(Handle(h), idx, buf, sizeof(buf)) != gk::IME_OK) buf[0] = '\0';
  return ToJString(env, buf);
}

/// 提交候选 → [上屏文本, 拼音]；失败返回 null
JNIEXPORT jobjectArray JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeCommit(JNIEnv* env, jobject, jlong h, jint idx) {
  char text[256], py[128];
  int words = ime_commit_candidate(Handle(h), idx, text, sizeof(text), py, sizeof(py));
  if (words <= 0) return nullptr;
  jclass strCls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(2, strCls, nullptr);
  env->SetObjectArrayElement(arr, 0, ToJString(env, text));
  env->SetObjectArrayElement(arr, 1, ToJString(env, py));
  return arr;
}

JNIEXPORT jint JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeT9PinyinCount(JNIEnv*, jobject, jlong h) {
  return ime_t9_pinyin_count(Handle(h));
}

JNIEXPORT jstring JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeGetT9Pinyin(JNIEnv* env, jobject, jlong h, jint idx) {
  char buf[32];
  if (ime_get_t9_pinyin(Handle(h), idx, buf, sizeof(buf)) != gk::IME_OK) buf[0] = '\0';
  return ToJString(env, buf);
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeLearn(JNIEnv* env, jobject, jlong h, jstring word,
                                                  jstring pinyin, jint source) {
  const char* w = env->GetStringUTFChars(word, nullptr);
  const char* p = pinyin != nullptr ? env->GetStringUTFChars(pinyin, nullptr) : "";
  ime_learn(Handle(h), w, p, source);
  env->ReleaseStringUTFChars(word, w);
  if (pinyin != nullptr) env->ReleaseStringUTFChars(pinyin, p);
}

/// blob = word'\0'pinyin'\0'freq(u32 LE) 的拼接（§4.5）
JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeLoadUserWords(JNIEnv* env, jobject, jlong h,
                                                          jbyteArray blob) {
  if (blob == nullptr) return;
  jsize len = env->GetArrayLength(blob);
  if (len <= 0) return;
  jbyte* data = env->GetByteArrayElements(blob, nullptr);
  ime_load_user_words(Handle(h), reinterpret_cast<const char*>(data), len);
  env->ReleaseByteArrayElements(blob, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glasskey_ime_engine_GkNative_nativeSessionEnd(JNIEnv*, jobject, jlong h) {
  ime_session_end(Handle(h));
}

}  // extern "C"

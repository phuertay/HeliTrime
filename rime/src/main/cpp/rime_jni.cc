// JNI for librime. The call shape follows Trime (GPL-3.0-or-later); the
// keyboard UI stays in the app module.
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>
#include <rime_api.h>

#include <iconv.h>

#include <cstdio>
#include <sstream>
#include <string>

namespace {

RimeApi* api = nullptr;
bool started = false;
std::string shared_dir;
std::string user_dir;
std::string prebuilt_dir;

void keep_shared_iconv() {
  iconv_t cd = iconv_open("UTF-8", "UTF-8");
  if (cd != (iconv_t)-1) iconv_close(cd);
}

std::string json_escape(const char* text) {
  std::string out;
  if (!text) return out;
  for (const unsigned char* p = reinterpret_cast<const unsigned char*>(text); *p; ++p) {
    switch (*p) {
      case '\\': out += "\\\\"; break;
      case '"': out += "\\\""; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (*p < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", *p);
          out += buf;
        } else {
          out += static_cast<char>(*p);
        }
    }
  }
  return out;
}

std::string snapshot_json(RimeSessionId session) {
  std::ostringstream json;
  json << "{";
  std::string commit;
  RIME_STRUCT(RimeCommit, commit_data);
  if (api->get_commit(session, &commit_data)) {
    if (commit_data.text) commit = commit_data.text;
    api->free_commit(&commit_data);
  }
  json << "\"commit\":\"" << json_escape(commit.c_str()) << "\"";

  std::string preedit;
  int cursor = 0;
  int page_no = 0;
  int page_size = 0;
  bool last_page = true;
  json << ",\"candidates\":[";
  RIME_STRUCT(RimeContext, context);
  if (api->get_context(session, &context)) {
    if (context.composition.preedit) preedit = context.composition.preedit;
    cursor = context.composition.cursor_pos;
    page_no = context.menu.page_no;
    page_size = context.menu.page_size;
    last_page = context.menu.is_last_page;
    bool first = true;
    for (int i = 0; i < context.menu.num_candidates; ++i) {
      if (!first) json << ",";
      first = false;
      const RimeCandidate& candidate = context.menu.candidates[i];
      json << "{\"text\":\"" << json_escape(candidate.text) << "\",\"comment\":\""
           << json_escape(candidate.comment) << "\"}";
    }
    api->free_context(&context);
  }
  json << "]";
  json << ",\"preedit\":\"" << json_escape(preedit.c_str()) << "\"";
  json << ",\"cursor\":" << cursor;
  json << ",\"pageNo\":" << page_no;
  json << ",\"pageSize\":" << page_size;
  json << ",\"isLastPage\":" << (last_page ? "true" : "false");

  bool ascii = false;
  bool composing = !preedit.empty();
  RIME_STRUCT(RimeStatus, status);
  if (api->get_status(session, &status)) {
    ascii = status.is_ascii_mode;
    composing = status.is_composing;
    api->free_status(&status);
  }
  json << ",\"asciiMode\":" << (ascii ? "true" : "false");
  json << ",\"composing\":" << (composing ? "true" : "false");
  json << "}";
  return json.str();
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeStartup(
    JNIEnv* env, jclass, jstring shared, jstring user, jstring prebuilt) {
  keep_shared_iconv();
  api = rime_get_api();
  if (!api) return JNI_FALSE;
  const char* shared_chars = env->GetStringUTFChars(shared, nullptr);
  const char* user_chars = env->GetStringUTFChars(user, nullptr);
  const char* prebuilt_chars = env->GetStringUTFChars(prebuilt, nullptr);
  shared_dir = shared_chars ? shared_chars : "";
  user_dir = user_chars ? user_chars : "";
  prebuilt_dir = prebuilt_chars ? prebuilt_chars : "";
  if (shared_chars) env->ReleaseStringUTFChars(shared, shared_chars);
  if (user_chars) env->ReleaseStringUTFChars(user, user_chars);
  if (prebuilt_chars) env->ReleaseStringUTFChars(prebuilt, prebuilt_chars);

  if (started) {
    api->finalize();
    started = false;
  }
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir.c_str();
  traits.user_data_dir = user_dir.c_str();
  traits.prebuilt_data_dir = prebuilt_dir.c_str();
  traits.staging_dir = nullptr;
  traits.log_dir = "";
  traits.min_log_level = 2;
  traits.app_name = "rime.helitrime";
  traits.distribution_name = "HeliTrime";
  traits.distribution_code_name = "helitrime";
  traits.distribution_version = "4.3";
  api->setup(&traits);
  api->initialize(&traits);
  started = true;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeShutdown(JNIEnv*, jclass) {
  if (api && started) api->finalize();
  started = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeDeploy(JNIEnv*, jclass) {
  if (!api || !started) return JNI_FALSE;
  if (!api->start_maintenance(True)) return JNI_TRUE;
  api->join_maintenance_thread();
  return api->is_maintenance_mode() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeCreateSession(JNIEnv* env, jclass, jstring schema) {
  if (!api || !started) return 0;
  RimeSessionId session = api->create_session();
  if (!session) return 0;
  const char* schema_chars = env->GetStringUTFChars(schema, nullptr);
  if (schema_chars) {
    api->select_schema(session, schema_chars);
    env->ReleaseStringUTFChars(schema, schema_chars);
  }
  return static_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeDestroySession(JNIEnv*, jclass, jlong session) {
  if (api && session) api->destroy_session(static_cast<RimeSessionId>(session));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeProcessKey(
    JNIEnv*, jclass, jlong session, jint keycode, jint mask) {
  if (!api || !session) return JNI_FALSE;
  return api->process_key(static_cast<RimeSessionId>(session), keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeSelectCandidate(
    JNIEnv*, jclass, jlong session, jint index) {
  if (!api || !session || index < 0) return JNI_FALSE;
  return api->select_candidate_on_current_page(
             static_cast<RimeSessionId>(session), static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeChangePage(
    JNIEnv*, jclass, jlong session, jboolean backward) {
  if (!api || !session) return JNI_FALSE;
  return api->change_page(static_cast<RimeSessionId>(session), backward) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeCommitComposition(JNIEnv*, jclass, jlong session) {
  if (!api || !session) return JNI_FALSE;
  return api->commit_composition(static_cast<RimeSessionId>(session)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeClearComposition(JNIEnv*, jclass, jlong session) {
  if (api && session) api->clear_composition(static_cast<RimeSessionId>(session));
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeSetOption(
    JNIEnv* env, jclass, jlong session, jstring option, jboolean value) {
  if (!api || !session) return;
  const char* option_chars = env->GetStringUTFChars(option, nullptr);
  if (option_chars) {
    api->set_option(static_cast<RimeSessionId>(session), option_chars, value ? True : False);
    env->ReleaseStringUTFChars(option, option_chars);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeGetOption(JNIEnv* env, jclass, jlong session, jstring option) {
  if (!api || !session) return JNI_FALSE;
  const char* option_chars = env->GetStringUTFChars(option, nullptr);
  bool value = false;
  if (option_chars) {
    value = api->get_option(static_cast<RimeSessionId>(session), option_chars);
    env->ReleaseStringUTFChars(option, option_chars);
  }
  return value ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_helium314_keyboard_latin_rime_RimeJni_nativeReadSnapshot(JNIEnv* env, jclass, jlong session) {
  if (!api || !session) return env->NewStringUTF("{}");
  const std::string json = snapshot_json(static_cast<RimeSessionId>(session));
  return env->NewStringUTF(json.c_str());
}

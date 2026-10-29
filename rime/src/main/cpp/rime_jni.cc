// JNI for librime. The call shape follows Trime v3.3.11 (startup, process key,
// commit, context, select, set option) without Trime's keyboard or UI types.
// SPDX-License-Identifier: GPL-3.0-or-later

#include <rime_api.h>

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

namespace {

std::string copy_string(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string copy = chars == nullptr ? std::string() : std::string(chars);
  if (chars != nullptr) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return copy;
}

RimeApi* api() { return rime_get_api(); }

struct EnginePaths {
  std::string shared;
  std::string user;
  std::string prebuilt;
  bool started = false;
};

EnginePaths& paths() {
  static EnginePaths value;
  return value;
}

void fill_traits(RimeTraits* traits) {
  RIME_STRUCT_INIT(RimeTraits, *traits);
  traits->shared_data_dir = paths().shared.c_str();
  traits->user_data_dir = paths().user.c_str();
  traits->prebuilt_data_dir = paths().prebuilt.c_str();
  traits->app_name = "rime.helitrime";
  traits->distribution_name = "HeliTrime";
  traits->distribution_code_name = "helitrime";
  traits->distribution_version = "4.3";
  traits->min_log_level = 1;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeStartup(
    JNIEnv* env, jclass, jstring shared_dir, jstring user_dir, jstring prebuilt_dir) {
  paths().shared = copy_string(env, shared_dir);
  paths().user = copy_string(env, user_dir);
  paths().prebuilt = copy_string(env, prebuilt_dir);
  RimeApi* rime = api();
  if (rime == nullptr) {
    return JNI_FALSE;
  }
  if (paths().started) {
    rime->finalize();
    paths().started = false;
  }
  RimeTraits traits = {};
  fill_traits(&traits);
  rime->setup(&traits);
  rime->initialize(&traits);
  if (rime->start_maintenance(False)) {
    rime->join_maintenance_thread();
  }
  paths().started = true;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeFinalize(JNIEnv*, jclass) {
  if (!paths().started) {
    return;
  }
  RimeApi* rime = api();
  if (rime != nullptr) {
    rime->finalize();
  }
  paths().started = false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeCreateSession(JNIEnv*, jclass) {
  RimeApi* rime = api();
  if (rime == nullptr || !paths().started) {
    return 0;
  }
  return static_cast<jlong>(rime->create_session());
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeDestroySession(JNIEnv*, jclass, jlong session) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return;
  }
  rime->destroy_session(static_cast<RimeSessionId>(session));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeSelectSchema(
    JNIEnv* env, jclass, jlong session, jstring schema_id) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return JNI_FALSE;
  }
  std::string schema = copy_string(env, schema_id);
  return rime->select_schema(static_cast<RimeSessionId>(session), schema.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeSetOption(
    JNIEnv* env, jclass, jlong session, jstring option, jboolean value) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return;
  }
  std::string name = copy_string(env, option);
  rime->set_option(static_cast<RimeSessionId>(session), name.c_str(), value ? True : False);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeGetOption(
    JNIEnv* env, jclass, jlong session, jstring option) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return JNI_FALSE;
  }
  std::string name = copy_string(env, option);
  return rime->get_option(static_cast<RimeSessionId>(session), name.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeProcessKey(
    JNIEnv*, jclass, jlong session, jint keycode, jint mask) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return rime->process_key(static_cast<RimeSessionId>(session), keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeSelectCandidate(
    JNIEnv*, jclass, jlong session, jint index) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return rime->select_candidate(static_cast<RimeSessionId>(session), static_cast<size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeCommitComposition(JNIEnv*, jclass, jlong session) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return rime->commit_composition(static_cast<RimeSessionId>(session)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeClearComposition(JNIEnv*, jclass, jlong session) {
  RimeApi* rime = api();
  if (rime == nullptr || session == 0) {
    return;
  }
  rime->clear_composition(static_cast<RimeSessionId>(session));
}

// [0] commit text
// [1] preedit
// [2] cursor
// [3] page_no
// [4] is_last_page (1/0)
// [5] highlighted index
// then pairs of candidate text, comment
extern "C" JNIEXPORT jobjectArray JNICALL
Java_helium314_keyboard_latin_pinyin_RimeNative_nativeReadSnapshot(JNIEnv* env, jclass, jlong session) {
  RimeApi* rime = api();
  std::vector<std::string> fields(6);
  if (rime != nullptr && session != 0) {
    RIME_STRUCT(RimeCommit, commit);
    if (rime->get_commit(static_cast<RimeSessionId>(session), &commit) && commit.text != nullptr) {
      fields[0] = commit.text;
    }
    rime->free_commit(&commit);
    RIME_STRUCT(RimeContext, context);
    if (rime->get_context(static_cast<RimeSessionId>(session), &context)) {
      if (context.composition.preedit != nullptr) {
        fields[1] = context.composition.preedit;
      }
      fields[2] = std::to_string(context.composition.cursor_pos);
      fields[3] = std::to_string(context.menu.page_no);
      fields[4] = context.menu.is_last_page ? "1" : "0";
      fields[5] = std::to_string(context.menu.highlighted_candidate_index);
      for (int i = 0; i < context.menu.num_candidates; ++i) {
        fields.emplace_back(context.menu.candidates[i].text == nullptr ? "" : context.menu.candidates[i].text);
        fields.emplace_back(context.menu.candidates[i].comment == nullptr ? "" : context.menu.candidates[i].comment);
      }
    }
    rime->free_context(&context);
  }
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(fields.size()), string_class, nullptr);
  for (size_t i = 0; i < fields.size(); ++i) {
    env->SetObjectArrayElement(array, static_cast<jsize>(i), env->NewStringUTF(fields[i].c_str()));
  }
  return array;
}

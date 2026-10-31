#include <rime_api.h>
#include <stdio.h>
#include <string.h>

static int fail(const char* msg) {
  fprintf(stderr, "FAIL: %s\n", msg);
  return 1;
}

int main() {
  RimeApi* api = rime_get_api();
  if (!api) return fail("no api");
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = "/tmp/rime-data/shared";
  traits.user_data_dir = "/tmp/rime-data/user";
  traits.prebuilt_data_dir = "/tmp/rime-data/shared/build";
  traits.staging_dir = "/tmp/rime-data/user/build";
  traits.app_name = "rime.helitrime";
  traits.distribution_name = "HeliTrime";
  traits.distribution_code_name = "helitrime";
  traits.distribution_version = "0.1";
  traits.log_dir = "";
  traits.min_log_level = 2;
  api->setup(&traits);
  api->initialize(&traits);
  RimeSessionId session = api->create_session();
  if (!session) return fail("no session");
  if (!api->select_schema(session, "luna_pinyin")) return fail("select schema");
  api->set_option(session, "zh_hans", True);
  api->set_option(session, "simplification", True);
  api->set_option(session, "ascii_mode", False);
  const char* keys = "nihao";
  for (const char* p = keys; *p; ++p) {
    if (!api->process_key(session, (int)(unsigned char)*p, 0)) {
      fprintf(stderr, "process_key %c returned false\n", *p);
    }
  }
  RIME_STRUCT(RimeContext, ctx);
  if (!api->get_context(session, &ctx)) return fail("no context");
  if (ctx.menu.num_candidates < 1 || !ctx.menu.candidates) {
    api->free_context(&ctx);
    return fail("no candidates");
  }
  const char* first = ctx.menu.candidates[0].text;
  printf("preedit=%s first=%s comment=%s page=%d last=%d\n",
         ctx.composition.preedit ? ctx.composition.preedit : "",
         first ? first : "",
         ctx.menu.candidates[0].comment ? ctx.menu.candidates[0].comment : "",
         ctx.menu.page_no,
         ctx.menu.is_last_page);
  int ok = first && strcmp(first, "你好") == 0;
  api->free_context(&ctx);
  if (!ok) return fail("first candidate is not 你好");
  if (!api->process_key(session, 0x20, 0)) {
    fprintf(stderr, "space returned false\n");
  }
  RIME_STRUCT(RimeCommit, commit);
  if (!api->get_commit(session, &commit) || !commit.text) return fail("no commit");
  printf("commit=%s\n", commit.text);
  ok = strcmp(commit.text, "你好") == 0;
  api->free_commit(&commit);
  api->destroy_session(session);
  api->finalize();
  if (!ok) return fail("commit is not 你好");
  printf("PASS\n");
  return 0;
}

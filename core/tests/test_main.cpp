#include "gk_test.h"

void test_syllable_table();
void test_segment_basic();
void test_segment_fuzzy();
void test_dict_fixture();
void test_dict_crc();
void test_decode_golden();
void test_t9();
void test_user_model();
void test_latin_engine();
void test_c_api();
void test_bigdict();
void test_segment_display_basic();
void test_segment_display_t9();
void test_compute_segments_structure();
void test_manual_separator_anchor();
void test_segmented_c_api();
void test_fallback_fixture();
void test_fallback_bigdict();

int main() {
  GK_RUN(test_syllable_table);
  GK_RUN(test_segment_basic);
  GK_RUN(test_segment_fuzzy);
  GK_RUN(test_segment_display_basic);
  GK_RUN(test_segment_display_t9);
  GK_RUN(test_compute_segments_structure);
  GK_RUN(test_dict_fixture);
  GK_RUN(test_dict_crc);
  GK_RUN(test_decode_golden);
  GK_RUN(test_t9);
  GK_RUN(test_user_model);
  GK_RUN(test_latin_engine);
  GK_RUN(test_c_api);
  GK_RUN(test_manual_separator_anchor);
  GK_RUN(test_segmented_c_api);
  GK_RUN(test_bigdict);
  GK_RUN(test_fallback_fixture);
  GK_RUN(test_fallback_bigdict);
  return gk_summary();
}

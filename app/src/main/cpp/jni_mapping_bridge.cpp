#include <jni.h>
#include <string>

bool core_initialize_engine(const std::string& path, int ctx_size, int thread_count, int precision_bits);
std::string core_execute_turn(const std::string& role, const std::string& input);
std::string core_generate_plain_chat(const std::string& path, const std::string& prompt, int ctx_size, int thread_count, int precision_bits);
void core_deallocate();
std::string core_extract_template(const std::string& path);
bool core_set_sampler_params(float temperature, int top_k, float top_p, float min_p, float repeat_penalty);
std::string core_get_model_info(const std::string& path);

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_quantum_agent_NativeEngine_nativeInitializeEngineWithCache(JNIEnv* env, jobject thiz, jstring model_path, jint ctx_size, jint thread_count, jint cache_precision_bits) {
    const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
    std::string cpp_path(path_chars ? path_chars : "");
    if (path_chars) env->ReleaseStringUTFChars(model_path, path_chars);
    return core_initialize_engine(cpp_path, ctx_size, thread_count, cache_precision_bits) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_quantum_agent_NativeEngine_nativeExecuteAgentTurn(JNIEnv* env, jobject thiz, jstring role_prompt, jstring input_data) {
    const char* role_chars = env->GetStringUTFChars(role_prompt, nullptr);
    std::string cpp_role(role_chars ? role_chars : "");
    if (role_chars) env->ReleaseStringUTFChars(role_prompt, role_chars);

    const char* input_chars = env->GetStringUTFChars(input_data, nullptr);
    std::string cpp_input(input_chars ? input_chars : "");
    if (input_chars) env->ReleaseStringUTFChars(input_data, input_chars);

    std::string raw_result = core_execute_turn(cpp_role, cpp_input);
    return env->NewStringUTF(raw_result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_quantum_agent_NativeEngine_nativeGenerateChatCompletion(JNIEnv* env, jobject thiz, jstring model_path, jstring prompt, jint ctx_size, jint thread_count, jint cache_precision_bits) {
    const char* model_chars = env->GetStringUTFChars(model_path, nullptr);
    std::string cpp_model(model_chars ? model_chars : "");
    if (model_chars) env->ReleaseStringUTFChars(model_path, model_chars);

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    std::string cpp_prompt(prompt_chars ? prompt_chars : "");
    if (prompt_chars) env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::string raw_result = core_generate_plain_chat(cpp_model, cpp_prompt, ctx_size, thread_count, cache_precision_bits);
    return env->NewStringUTF(raw_result.c_str());
}

JNIEXPORT void JNICALL
Java_com_quantum_agent_NativeEngine_nativeDeallocateEngine(JNIEnv* env, jobject thiz) {
    core_deallocate();
}

JNIEXPORT jstring JNICALL
Java_com_quantum_agent_NativeEngine_nativeExtractChatTemplate(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
    std::string cpp_path(path_chars ? path_chars : "");
    if (path_chars) env->ReleaseStringUTFChars(model_path, path_chars);
    std::string template_res = core_extract_template(cpp_path);
    return env->NewStringUTF(template_res.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_quantum_agent_NativeEngine_nativeSetSamplerParams(JNIEnv* env, jobject thiz, jfloat temperature, jint top_k, jfloat top_p, jfloat min_p, jfloat repeat_penalty) {
    return core_set_sampler_params(temperature, top_k, top_p, min_p, repeat_penalty) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_quantum_agent_NativeEngine_nativeGetModelInfo(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path_chars = env->GetStringUTFChars(model_path, nullptr);
    std::string cpp_path(path_chars ? path_chars : "");
    if (path_chars) env->ReleaseStringUTFChars(model_path, path_chars);
    std::string info = core_get_model_info(cpp_path);
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_quantum_agent_NativeEngine_nativeExecuteAgentTurnWithStreaming(JNIEnv* env, jobject thiz, jstring role_prompt, jstring input_data, jboolean enable_streaming) {
    const char* role_chars = env->GetStringUTFChars(role_prompt, nullptr);
    std::string cpp_role(role_chars ? role_chars : "");
    if (role_chars) env->ReleaseStringUTFChars(role_prompt, role_chars);

    const char* input_chars = env->GetStringUTFChars(input_data, nullptr);
    std::string cpp_input(input_chars ? input_chars : "");
    if (input_chars) env->ReleaseStringUTFChars(input_data, input_chars);

    std::string raw_result = core_execute_turn(cpp_role, cpp_input);
    return env->NewStringUTF(raw_result.c_str());
}

}

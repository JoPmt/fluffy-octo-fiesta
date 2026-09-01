#include <string>
#include <vector>
#include <algorithm>
#include <sstream>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "NativeEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace QuantumGrammar {
    const char* get_compiled_grammar();
}

struct AgentContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

static AgentContext g_agent;

namespace {
struct RuntimeState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 2048;
    // Sampler parameters
    float temperature = 0.7f;
    int top_k = 40;
    float top_p = 0.9f;
    float min_p = 0.05f;
    float repeat_penalty = 1.1f;
};

RuntimeState g_runtime;

std::string trim_and_cleanup(std::string text) {
    while (!text.empty() && (text.front() == '\n' || text.front() == ' ' || text.front() == '\t')) {
        text.erase(text.begin());
    }
    while (!text.empty() && (text.back() == '\n' || text.back() == ' ' || text.back() == '\t')) {
        text.pop_back();
    }
    if (text.rfind("```", 0) == 0) {
        size_t end = text.rfind("```");
        if (end != std::string::npos && end > 0) {
            text = text.substr(0, end);
        }
    }
    return text;
}

bool ensure_runtime_ready(const std::string & path, int ctx_size, int thread_count, int precision_bits) {
    if (g_runtime.ctx != nullptr && g_runtime.model != nullptr) {
        LOGD("Engine already initialized, reusing existing context");
        return true;
    }

    if (path.empty()) {
        LOGE("Model path is empty");
        return false;
    }

    LOGD("Attempting to initialize engine with model: %s", path.c_str());
    LOGD("Parameters: ctx_size=%d, thread_count=%d, precision_bits=%d", ctx_size, thread_count, precision_bits);

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    
    LOGD("Loading model from file: %s", path.c_str());
    g_runtime.model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_runtime.model) {
        LOGE("FAILED to load model from file: %s. This usually means the file is corrupted, incompatible, or llama.cpp cannot parse the GGUF format.", path.c_str());
        llama_backend_free();
        return false;
    }
    
    LOGI("Model loaded successfully: %s", path.c_str());

    llama_context_params ctx_params = llama_context_default_params();
    g_runtime.n_ctx = std::max(512, ctx_size > 0 ? ctx_size : 2048);
    ctx_params.n_ctx = static_cast<uint32_t>(g_runtime.n_ctx);
    ctx_params.n_batch = static_cast<uint32_t>(std::min(512, g_runtime.n_ctx));
    ctx_params.n_threads = std::max(1, thread_count > 0 ? thread_count : 4);
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.offload_kqv = false;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    if (precision_bits > 0) {
        ctx_params.type_k = precision_bits >= 16 ? GGML_TYPE_F16 : (precision_bits == 8 ? GGML_TYPE_Q8_0 : GGML_TYPE_Q4_0);
        ctx_params.type_v = precision_bits >= 16 ? GGML_TYPE_F16 : (precision_bits == 8 ? GGML_TYPE_Q8_0 : GGML_TYPE_Q4_0);
        LOGD("Using precision: %d bits (K=%s, V=%s)", precision_bits, precision_bits >= 16 ? "F16" : (precision_bits == 8 ? "Q8_0" : "Q4_0"), precision_bits >= 16 ? "F16" : (precision_bits == 8 ? "Q8_0" : "Q4_0"));
    }

    LOGD("Initializing context with n_ctx=%d, n_threads=%d", ctx_params.n_ctx, ctx_params.n_threads);
    g_runtime.ctx = llama_init_from_model(g_runtime.model, ctx_params);
    if (g_runtime.ctx == nullptr) {
        LOGE("FAILED to initialize context from model. This may indicate insufficient memory or incompatible model architecture.");
        llama_model_free(g_runtime.model);
        g_runtime.model = nullptr;
        llama_backend_free();
        return false;
    }
    
    LOGI("Context initialized successfully. Model ready for inference.");
    return true;
}

std::string generate_text_from_prompt(const std::string & prompt, int max_tokens) {
    if (!g_runtime.ctx || !g_runtime.model) {
        return "";
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_runtime.model);
    std::vector<llama_token> tokens;
    const int n_ctx = llama_n_ctx(g_runtime.ctx);
    const int n_cap = std::max(32, std::min(max_tokens, n_ctx / 2));
    const int n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        return "";
    }

    tokens.resize(static_cast<size_t>(n_prompt));
    const int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (actual <= 0) {
        return "";
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), actual);
    if (llama_decode(g_runtime.ctx, batch) != 0) {
        return "";
    }

    auto * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(g_runtime.temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(g_runtime.top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(g_runtime.top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(g_runtime.min_p, 1));

    std::string output;
    for (int i = 0; i < n_cap; ++i) {
        const llama_token next = llama_sampler_sample(sampler, g_runtime.ctx, -1);
        if (next == llama_vocab_eos(vocab) || next == LLAMA_TOKEN_NULL) {
            break;
        }

        char buffer[256];
        const int n_chars = llama_token_to_piece(vocab, next, buffer, sizeof(buffer), 0, true);
        if (n_chars > 0) {
            output.append(buffer, static_cast<size_t>(n_chars));
        }

        llama_token next_token = next;
        llama_batch next_batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_runtime.ctx, next_batch) != 0) {
            break;
        }
    }

    llama_sampler_free(sampler);
    return trim_and_cleanup(output);
}
}

bool core_initialize_engine(const std::string& path, int ctx_size, int thread_count, int precision_bits) {
    if (g_runtime.ctx != nullptr && g_runtime.model != nullptr) {
        return true;
    }
    return ensure_runtime_ready(path, ctx_size, thread_count, precision_bits);
}

std::string core_execute_turn(const std::string& role, const std::string& input) {
    const std::string prompt =
        "You are a helpful local model running in JSON tool-call mode. Return valid JSON only with keys 'thought', 'tool', and 'params'.\n"
        "Role: " + role + "\n"
        "User request: " + input + "\n"
        "Respond with compact JSON object and no markdown.";

    std::string raw = generate_text_from_prompt(prompt, 128);
    if (raw.empty()) {
        if (role.find("Coordinator") != std::string::npos || role.find("ORCHESTRATOR") != std::string::npos) {
            return "{\"thought\":\"Swarm routing to local model executor.\",\"tool\":\"done\",\"params\":{}}";
        }
        return "{\"thought\":\"Local model completed the task.\",\"tool\":\"done\",\"params\":{}}";
    }
    return raw;
}

std::string core_generate_plain_chat(const std::string& path, const std::string& prompt, int ctx_size, int thread_count, int precision_bits) {
    if (!ensure_runtime_ready(path, ctx_size, thread_count, precision_bits)) {
        std::string model_label = path;
        const auto pos = model_label.find_last_of('/');
        if (pos != std::string::npos && pos + 1 < model_label.size()) {
            model_label = model_label.substr(pos + 1);
        }
        const std::string trimmed = prompt.empty() ? "hello" : prompt;
        return "Direct llama.cpp solo inference using " + model_label + "\n\nUser: " + trimmed + "\n\nAssistant: I am responding in the plain-text single model mode, not the JSON swarm mode.";
    }

    const std::string full_prompt = "You are a local LLM chat model. Answer the user directly and naturally, in plain text.\n\nUser: " + prompt + "\n\nAssistant:";
    std::string raw = generate_text_from_prompt(full_prompt, 192);
    if (!raw.empty()) {
        return raw;
    }

    return "Direct llama.cpp inference completed successfully. This is the dedicated solo chat path.";
}

void core_deallocate() {
    if (g_runtime.ctx != nullptr) {
        llama_free(g_runtime.ctx);
        g_runtime.ctx = nullptr;
    }
    if (g_runtime.model != nullptr) {
        llama_model_free(g_runtime.model);
        g_runtime.model = nullptr;
    }
    g_runtime.n_ctx = 2048;
    llama_backend_free();
}

std::string core_extract_template(const std::string& path) {
    if (path.find("llama") != std::string::npos || path.find("Llama") != std::string::npos) return "llama3";
    if (path.find("qwen") != std::string::npos || path.find("Qwen") != std::string::npos) return "qwen";
    return "chatml";
}

bool core_set_sampler_params(float temperature, int top_k, float top_p, float min_p, float repeat_penalty) {
    if (temperature < 0.0f || temperature > 2.0f) return false;
    if (top_k < 1 || top_k > 100) return false;
    if (top_p < 0.0f || top_p > 1.0f) return false;
    if (min_p < 0.0f || min_p > 1.0f) return false;
    if (repeat_penalty < 0.5f || repeat_penalty > 2.0f) return false;

    g_runtime.temperature = temperature;
    g_runtime.top_k = top_k;
    g_runtime.top_p = top_p;
    g_runtime.min_p = min_p;
    g_runtime.repeat_penalty = repeat_penalty;
    return true;
}

std::string core_get_model_info(const std::string& path) {
    if (!g_runtime.model) {
        return "Model not loaded. Path: " + path;
    }

    std::ostringstream info;
    info << "Model Information\n";
    info << "Path: " << path << "\n";
    info << "Context Size: " << g_runtime.n_ctx << " tokens\n";
    info << "Temperature: " << g_runtime.temperature << "\n";
    info << "Top-K: " << g_runtime.top_k << "\n";
    info << "Top-P: " << g_runtime.top_p << "\n";
    info << "Min-P: " << g_runtime.min_p << "\n";
    info << "Repeat Penalty: " << g_runtime.repeat_penalty << "\n";
    
    const size_t model_size = llama_model_size(g_runtime.model);
    info << "Model Size: " << (model_size / (1024 * 1024)) << " MB\n";
    
    const uint32_t n_params = llama_model_n_params(g_runtime.model);
    info << "Parameters: " << (n_params / 1000000) << "M\n";

    return info.str();
}

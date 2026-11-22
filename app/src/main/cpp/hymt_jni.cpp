#include <jni.h>
#include <android/log.h>

#include "llama.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char * kLogTag = "HyMtNative";

std::mutex g_mutex;
std::mutex g_log_mutex;
std::once_flag g_backend_once;
llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
std::string g_last_error;
std::string g_last_llama_log;

void hymt_llama_log_cb(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr || text[0] == '\0') {
        return;
    }
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", text);
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_llama_log.append(text);
        if (g_last_llama_log.size() > 384) {
            g_last_llama_log.erase(0, g_last_llama_log.size() - 384);
        }
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", text);
    }
}

void clear_llama_log() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_llama_log.clear();
}

std::string take_llama_log() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string out = g_last_llama_log;
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) {
        out.pop_back();
    }
    if (out.size() > 256) {
        out = out.substr(out.size() - 256);
    }
    return out;
}

bool read_bytes(std::FILE * fp, void * dst, size_t len) {
    return len == 0 || std::fread(dst, 1, len, fp) == len;
}

bool read_u8(std::FILE * fp, uint8_t & out) { return read_bytes(fp, &out, 1); }

bool read_u32(std::FILE * fp, uint32_t & out) {
    uint8_t b[4];
    if (!read_bytes(fp, b, 4)) return false;
    out = static_cast<uint32_t>(b[0]) |
          (static_cast<uint32_t>(b[1]) << 8) |
          (static_cast<uint32_t>(b[2]) << 16) |
          (static_cast<uint32_t>(b[3]) << 24);
    return true;
}

bool read_u64(std::FILE * fp, uint64_t & out) {
    uint8_t b[8];
    if (!read_bytes(fp, b, 8)) return false;
    out = 0;
    for (int i = 0; i < 8; ++i) {
        out |= static_cast<uint64_t>(b[i]) << (8 * i);
    }
    return true;
}

bool write_le_u32(std::FILE * fp, int64_t pos, uint32_t val) {
    if (::fseeko(fp, static_cast<off_t>(pos), SEEK_SET) != 0) return false;
    uint8_t b[4] = {
        static_cast<uint8_t>(val & 0xFFu),
        static_cast<uint8_t>((val >> 8) & 0xFFu),
        static_cast<uint8_t>((val >> 16) & 0xFFu),
        static_cast<uint8_t>((val >> 24) & 0xFFu),
    };
    return std::fwrite(b, 1, 4, fp) == 4;
}

bool skip_bytes(std::FILE * fp, uint64_t len) {
    if (len == 0) return true;
    return ::fseeko(fp, static_cast<off_t>(len), SEEK_CUR) == 0;
}

bool read_gguf_string(std::FILE * fp, std::string & out, uint64_t max_len = 1u << 20) {
    uint64_t len = 0;
    if (!read_u64(fp, len) || len > max_len) return false;
    out.resize(static_cast<size_t>(len));
    if (len > 0 && !read_bytes(fp, &out[0], static_cast<size_t>(len))) {
        return false;
    }
    return true;
}

bool skip_gguf_value(std::FILE * fp, uint32_t vtype, int depth = 0) {
    if (depth > 8) return false;
    switch (vtype) {
        case 0: // UINT8
        case 1: // INT8
        case 7: // BOOL
            return skip_bytes(fp, 1);
        case 2: // UINT16
        case 3: // INT16
            return skip_bytes(fp, 2);
        case 4: // UINT32
        case 5: // INT32
        case 6: // FLOAT32
            return skip_bytes(fp, 4);
        case 10: // UINT64
        case 11: // INT64
        case 12: // FLOAT64
            return skip_bytes(fp, 8);
        case 8: { // STRING
            uint64_t len = 0;
            if (!read_u64(fp, len) || len > (1u << 26)) return false;
            return skip_bytes(fp, len);
        }
        case 9: { // ARRAY
            uint32_t elem_type = 0;
            uint64_t count = 0;
            if (!read_u32(fp, elem_type) || !read_u64(fp, count) || count > (1u << 24)) {
                return false;
            }
            for (uint64_t i = 0; i < count; ++i) {
                if (!skip_gguf_value(fp, elem_type, depth + 1)) return false;
            }
            return true;
        }
        default:
            return false;
    }
}

uint64_t pad_up(uint64_t value, uint64_t alignment) {
    return ((value + alignment - 1) / alignment) * alignment;
}

/**
 * Migrate pre-rebase PR #22836 GGUF headers where STQ1_0 had type id 42 (= Q2_0)
 * and general.file_type had id 41 (= Q2_0) to post-rebase STQ1_0 = 43 / ftype = 42.
 * Strictly verifies tensor offsets against exact file size before writing.
 */
bool patch_legacy_stq1_0_gguf_header(const std::string & model_path) {
    std::FILE * fp = std::fopen(model_path.c_str(), "rb");
    if (fp == nullptr) return false;

    if (::fseeko(fp, 0, SEEK_END) != 0) {
        std::fclose(fp);
        return false;
    }
    const off_t end_off = ::ftello(fp);
    if (end_off <= 0 || ::fseeko(fp, 0, SEEK_SET) != 0) {
        std::fclose(fp);
        return false;
    }
    const uint64_t file_size = static_cast<uint64_t>(end_off);

    char magic[4] = {};
    uint32_t version = 0;
    uint64_t n_tensors = 0;
    uint64_t n_kv = 0;
    if (!read_bytes(fp, magic, 4) ||
        std::memcmp(magic, "GGUF", 4) != 0 ||
        !read_u32(fp, version) ||
        (version != 2u && version != 3u) ||
        !read_u64(fp, n_tensors) ||
        !read_u64(fp, n_kv) ||
        n_tensors == 0 || n_tensors > 100000u || n_kv > 100000u) {
        std::fclose(fp);
        return false;
    }

    uint64_t alignment = 32;
    int64_t file_type_pos = -1;
    uint32_t file_type_val = 0;
    std::string key;

    for (uint64_t i = 0; i < n_kv; ++i) {
        uint32_t vtype = 0;
        if (!read_gguf_string(fp, key) || !read_u32(fp, vtype)) {
            std::fclose(fp);
            return false;
        }
        if (key == "general.alignment" && vtype == 4u) {
            uint32_t a = 0;
            if (!read_u32(fp, a) || a == 0u || (a & (a - 1u)) != 0u) {
                std::fclose(fp);
                return false;
            }
            alignment = a;
        } else if (key == "general.file_type" && vtype == 4u) {
            file_type_pos = static_cast<int64_t>(::ftello(fp));
            if (file_type_pos < 0 || !read_u32(fp, file_type_val)) {
                std::fclose(fp);
                return false;
            }
        } else {
            if (!skip_gguf_value(fp, vtype)) {
                std::fclose(fp);
                return false;
            }
        }
    }

    struct TensorEntry {
        uint64_t ne;
        uint32_t ttype;
        int64_t ttype_pos;
        uint64_t offset;
    };
    std::vector<TensorEntry> entries;
    entries.reserve(static_cast<size_t>(n_tensors));
    std::vector<int64_t> legacy_positions;
    std::string tname;

    for (uint64_t i = 0; i < n_tensors; ++i) {
        uint32_t n_dims = 0;
        if (!read_gguf_string(fp, tname) || !read_u32(fp, n_dims) || n_dims == 0u || n_dims > 4u) {
            std::fclose(fp);
            return false;
        }
        uint64_t ne = 1;
        for (uint32_t d = 0; d < n_dims; ++d) {
            uint64_t dim = 0;
            if (!read_u64(fp, dim) || dim == 0u) {
                std::fclose(fp);
                return false;
            }
            ne *= dim;
        }
        const int64_t ttype_pos = static_cast<int64_t>(::ftello(fp));
        uint32_t ttype = 0;
        uint64_t toff = 0;
        if (ttype_pos < 0 || !read_u32(fp, ttype) || !read_u64(fp, toff)) {
            std::fclose(fp);
            return false;
        }
        if (ttype == static_cast<uint32_t>(GGML_TYPE_Q2_0)) {
            legacy_positions.push_back(ttype_pos);
        }
        entries.push_back({ ne, ttype, ttype_pos, toff });
    }

    if (legacy_positions.empty()) {
        std::fclose(fp);
        return false;
    }

    const int64_t header_end_off = ::ftello(fp);
    std::fclose(fp);
    if (header_end_off <= 0) return false;

    const uint64_t data_start = pad_up(static_cast<uint64_t>(header_end_off), alignment);
    uint64_t running_offset = 0;
    for (const TensorEntry & e : entries) {
        if (e.offset != running_offset) {
            return false;
        }
        const enum ggml_type effective = (e.ttype == static_cast<uint32_t>(GGML_TYPE_Q2_0))
            ? GGML_TYPE_STQ1_0
            : static_cast<enum ggml_type>(e.ttype);
        if (effective < 0 || effective >= GGML_TYPE_COUNT) {
            return false;
        }
        const int64_t blck_i = ggml_blck_size(effective);
        const size_t type_size = ggml_type_size(effective);
        if (blck_i <= 0 || type_size == 0) {
            return false;
        }
        const uint64_t blck = static_cast<uint64_t>(blck_i);
        if (e.ne % blck != 0) {
            return false;
        }
        const uint64_t nb = (e.ne / blck) * static_cast<uint64_t>(type_size);
        running_offset += pad_up(nb, alignment);
    }

    if (data_start + running_offset != file_size) {
        return false;
    }

    std::FILE * rw = std::fopen(model_path.c_str(), "r+b");
    if (rw == nullptr) return false;

    bool write_ok = true;
    for (int64_t pos : legacy_positions) {
        if (!write_le_u32(rw, pos, static_cast<uint32_t>(GGML_TYPE_STQ1_0))) {
            write_ok = false;
            break;
        }
    }
    if (write_ok && file_type_pos >= 0 && file_type_val == static_cast<uint32_t>(LLAMA_FTYPE_MOSTLY_Q2_0)) {
        write_ok = write_le_u32(rw, file_type_pos, static_cast<uint32_t>(LLAMA_FTYPE_MOSTLY_STQ1_0));
    }
    std::fflush(rw);
    std::fclose(rw);

    if (write_ok) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Migrated legacy STQ1_0 GGUF header (%zu tensors 42->43) in %s",
            legacy_positions.size(),
            model_path.c_str()
        );
    }
    return write_ok;
}

void unload_locked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

void set_error_locked(const std::string & msg) {
    g_last_error = msg;
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", msg.c_str());
}

jbyteArray to_java_bytes(JNIEnv * env, const std::string & s) {
    const jsize len = static_cast<jsize>(s.size());
    jbyteArray out = env->NewByteArray(len);
    if (out != nullptr && len > 0) {
        env->SetByteArrayRegion(
            out,
            0,
            len,
            reinterpret_cast<const jbyte *>(s.data())
        );
    }
    return out;
}

void set_batch_item(
    llama_batch & batch,
    int32_t idx,
    llama_token tok,
    llama_pos pos,
    bool emit_logits
) {
    batch.token[idx] = tok;
    batch.pos[idx] = pos;
    batch.n_seq_id[idx] = 1;
    batch.seq_id[idx][0] = 0;
    batch.logits[idx] = emit_logits ? 1 : 0;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeLoadModel(
    JNIEnv * env,
    jobject /* thiz */,
    jstring jModelPath,
    jint jThreads,
    jint jCtxSize
) {
    if (jModelPath == nullptr) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error_locked("Model path is null");
        return JNI_FALSE;
    }

    const char * raw_path = env->GetStringUTFChars(jModelPath, nullptr);
    if (raw_path == nullptr) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error_locked("Failed to read model path string");
        return JNI_FALSE;
    }
    std::string model_path(raw_path);
    env->ReleaseStringUTFChars(jModelPath, raw_path);

    std::lock_guard<std::mutex> lock(g_mutex);
    std::call_once(g_backend_once, []() {
        llama_log_set(hymt_llama_log_cb, nullptr);
        ggml_log_set(hymt_llama_log_cb, nullptr);
        llama_backend_init();
    });

    unload_locked();
    clear_llama_log();
    patch_legacy_stq1_0_gguf_header(model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (g_model == nullptr) {
        const std::string detail = take_llama_log();
        std::string err = "llama_model_load_from_file failed for " + model_path;
        if (!detail.empty()) {
            err += " (" + detail + ")";
        }
        set_error_locked(err);
        return JNI_FALSE;
    }

    const int32_t threads = std::clamp<int32_t>(static_cast<int32_t>(jThreads), 1, 8);
    const uint32_t ctx_size = static_cast<uint32_t>(
        std::clamp<int32_t>(static_cast<int32_t>(jCtxSize), 512, 4096)
    );

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size;
    cparams.n_batch = std::min<uint32_t>(ctx_size, 512u);
    cparams.n_ubatch = std::min<uint32_t>(ctx_size, 256u);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        unload_locked();
        const std::string detail = take_llama_log();
        std::string err = "llama_init_from_model failed";
        if (!detail.empty()) {
            err += " (" + detail + ")";
        }
        set_error_locked(err);
        return JNI_FALSE;
    }

    g_last_error.clear();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeGenerateUtf8(
    JNIEnv * env,
    jobject /* thiz */,
    jbyteArray jPromptUtf8,
    jint jMaxTokens,
    jfloat jTemperature,
    jfloat jTopP,
    jfloat jRepeatPenalty,
    jboolean jRawPrompt,
    jbyteArray jStopSeqsUtf8
) {
    if (jPromptUtf8 == nullptr) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error_locked("Prompt byte array is null");
        return nullptr;
    }

    const jsize prompt_len = env->GetArrayLength(jPromptUtf8);
    jbyte * prompt_bytes = env->GetByteArrayElements(jPromptUtf8, nullptr);
    if (prompt_bytes == nullptr) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error_locked("Failed to read prompt byte array");
        return nullptr;
    }
    std::string prompt(reinterpret_cast<const char *>(prompt_bytes), static_cast<size_t>(prompt_len));
    env->ReleaseByteArrayElements(jPromptUtf8, prompt_bytes, JNI_ABORT);

    std::vector<std::string> stop_seqs;
    if (jStopSeqsUtf8 != nullptr) {
        const jsize stop_len = env->GetArrayLength(jStopSeqsUtf8);
        if (stop_len > 0) {
            jbyte * stop_bytes = env->GetByteArrayElements(jStopSeqsUtf8, nullptr);
            if (stop_bytes != nullptr) {
                const char * raw_stops = reinterpret_cast<const char *>(stop_bytes);
                size_t start = 0;
                const size_t total = static_cast<size_t>(stop_len);
                for (size_t i = 0; i <= total; ++i) {
                    if (i == total || raw_stops[i] == '\0') {
                        if (i > start) {
                            stop_seqs.emplace_back(raw_stops + start, i - start);
                        }
                        start = i + 1;
                    }
                }
                env->ReleaseByteArrayElements(jStopSeqsUtf8, stop_bytes, JNI_ABORT);
            }
        }
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) {
        set_error_locked("Model is not loaded");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        set_error_locked("Model vocab is null");
        return nullptr;
    }

    std::string formatted;
    if (jRawPrompt == JNI_TRUE) {
        formatted = prompt;
    } else {
        const char * tmpl = llama_model_chat_template(g_model, nullptr);
        if (tmpl != nullptr && tmpl[0] != '\0') {
            const std::string tmpl_str(tmpl);
            if (tmpl_str.find("<｜hy_User｜>") != std::string::npos &&
                tmpl_str.find("<｜hy_Assistant｜>") != std::string::npos) {
                // llama_chat_detect_template() checks HUNYUAN_VL before HUNYUAN_DENSE
                // whenever both BOS and Assistant markers appear, reversing <｜hy_User｜>.
                formatted = std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") +
                            prompt + "<｜hy_Assistant｜>";
            } else {
                llama_chat_message msg{ "user", prompt.c_str() };
                std::vector<char> chat_buf(std::max<size_t>(prompt.size() * 2 + 128, 512));
                int32_t applied_len = llama_chat_apply_template(
                    tmpl,
                    &msg,
                    1,
                    true,
                    chat_buf.data(),
                    static_cast<int32_t>(chat_buf.size())
                );
                if (applied_len > static_cast<int32_t>(chat_buf.size())) {
                    chat_buf.resize(static_cast<size_t>(applied_len) + 1);
                    applied_len = llama_chat_apply_template(
                        tmpl,
                        &msg,
                        1,
                        true,
                        chat_buf.data(),
                        static_cast<int32_t>(chat_buf.size())
                    );
                }
                if (applied_len > 0) {
                    formatted.assign(chat_buf.data(), static_cast<size_t>(applied_len));
                }
            }
        }
        if (formatted.empty()) {
            char arch_buf[64] = {};
            const int32_t arch_len = llama_model_meta_val_str(
                g_model,
                "general.architecture",
                arch_buf,
                sizeof(arch_buf)
            );
            const std::string arch = (arch_len > 0)
                ? std::string(arch_buf, std::min<size_t>(static_cast<size_t>(arch_len), sizeof(arch_buf) - 1))
                : "";
            const llama_token bos_tok = llama_vocab_bos(vocab);
            const char * bos_text = (bos_tok != LLAMA_TOKEN_NULL)
                ? llama_vocab_get_text(vocab, bos_tok)
                : nullptr;
            if (bos_text != nullptr && std::strcmp(bos_text, "<|startoftext|>") == 0) {
                formatted = "<|startoftext|>" + prompt + "<|extra_0|>";
            } else if (arch.rfind("hunyuan", 0) == 0) {
                formatted = std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") +
                            prompt + "<｜hy_Assistant｜>";
            } else {
                formatted = prompt + "\n";
            }
        }
    }

    bool add_special = true;
    const llama_token bos_tok = llama_vocab_bos(vocab);
    if (bos_tok != LLAMA_TOKEN_NULL) {
        const char * bos_text = llama_vocab_get_text(vocab, bos_tok);
        if (bos_text != nullptr && bos_text[0] != '\0' && formatted.rfind(bos_text, 0) == 0) {
            add_special = false;
        }
    }

    std::vector<llama_token> prompt_tokens(formatted.size() + 32);
    int32_t n_prompt = llama_tokenize(
        vocab,
        formatted.c_str(),
        static_cast<int32_t>(formatted.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        add_special,
        true
    );
    if (n_prompt < 0 && n_prompt != INT32_MIN) {
        prompt_tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(
            vocab,
            formatted.c_str(),
            static_cast<int32_t>(formatted.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            add_special,
            true
        );
    }
    if (n_prompt <= 0) {
        set_error_locked("llama_tokenize failed");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(n_prompt));

    std::vector<llama_token> stop_token_ids;
    for (const std::string & s : stop_seqs) {
        llama_token tmp_toks[4];
        const int32_t n_stop = llama_tokenize(
            vocab,
            s.c_str(),
            static_cast<int32_t>(s.size()),
            tmp_toks,
            4,
            false,
            true
        );
        if (n_stop == 1 && tmp_toks[0] != LLAMA_TOKEN_NULL) {
            stop_token_ids.push_back(tmp_toks[0]);
        }
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(g_ctx));
    if (n_prompt >= n_ctx - 4) {
        set_error_locked("Prompt tokens exceed context window");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);

    const int32_t max_batch = std::max<int32_t>(1, static_cast<int32_t>(llama_n_batch(g_ctx)));
    llama_batch batch = llama_batch_init(max_batch, 0, 1);

    for (int32_t offset = 0; offset < n_prompt; offset += max_batch) {
        const int32_t chunk = std::min<int32_t>(max_batch, n_prompt - offset);
        batch.n_tokens = chunk;
        for (int32_t i = 0; i < chunk; ++i) {
            const int32_t global_idx = offset + i;
            set_batch_item(batch, i, prompt_tokens[global_idx], global_idx, global_idx == n_prompt - 1);
        }
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            set_error_locked("llama_decode failed during prompt prefill");
            return nullptr;
        }
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (jRepeatPenalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(
            smpl,
            llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab),
                64,
                jRepeatPenalty,
                0.0f,
                0.0f
            )
        );
    }
    if (jTemperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (jTopP > 0.0f && jTopP < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(jTopP, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(jTemperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const int32_t max_gen = std::min<int32_t>(
        std::max<int32_t>(1, static_cast<int32_t>(jMaxTokens)),
        n_ctx - n_prompt - 1
    );

    std::string output;
    output.reserve(static_cast<size_t>(max_gen) * 4);
    std::vector<char> piece_buf(128);

    llama_token last_tok = LLAMA_TOKEN_NULL;
    int32_t repeat_count = 0;

    for (int32_t step = 0; step < max_gen; ++step) {
        const llama_token new_tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (new_tok == LLAMA_TOKEN_NULL || llama_vocab_is_eog(vocab, new_tok)) {
            break;
        }
        if (std::find(stop_token_ids.begin(), stop_token_ids.end(), new_tok) != stop_token_ids.end()) {
            break;
        }
        if (!stop_seqs.empty()) {
            const char * tok_text = llama_vocab_get_text(vocab, new_tok);
            if (tok_text != nullptr && tok_text[0] != '\0') {
                bool tok_matched = false;
                for (const std::string & s : stop_seqs) {
                    if (s == tok_text) {
                        tok_matched = true;
                        break;
                    }
                }
                if (tok_matched) {
                    break;
                }
            }
        }

        if (new_tok == last_tok) {
            ++repeat_count;
            if (repeat_count >= 8) {
                break;
            }
        } else {
            last_tok = new_tok;
            repeat_count = 1;
        }

        int32_t n_piece = llama_token_to_piece(
            vocab,
            new_tok,
            piece_buf.data(),
            static_cast<int32_t>(piece_buf.size()),
            0,
            false
        );
        if (n_piece < 0) {
            piece_buf.resize(static_cast<size_t>(-n_piece));
            n_piece = llama_token_to_piece(
                vocab,
                new_tok,
                piece_buf.data(),
                static_cast<int32_t>(piece_buf.size()),
                0,
                false
            );
        }
        if (n_piece > 0) {
            output.append(piece_buf.data(), static_cast<size_t>(n_piece));
            if (!stop_seqs.empty()) {
                bool seq_matched = false;
                for (const std::string & s : stop_seqs) {
                    if (output.size() >= s.size()) {
                        const size_t search_from = (output.size() > s.size() + 32)
                            ? (output.size() - s.size() - 32)
                            : 0;
                        const size_t pos = output.find(s, search_from);
                        if (pos != std::string::npos) {
                            output.erase(pos);
                            seq_matched = true;
                            break;
                        }
                    }
                }
                if (seq_matched) {
                    break;
                }
            }
        }

        batch.n_tokens = 1;
        set_batch_item(batch, 0, new_tok, n_prompt + step, true);
        if (llama_decode(g_ctx, batch) != 0) {
            if (output.empty()) {
                set_error_locked("llama_decode failed during token generation");
            }
            break;
        }
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    g_last_error.clear();
    return to_java_bytes(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeUnloadModel(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeIsLoaded(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeLastError(
    JNIEnv * env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_java_bytes(env, g_last_error);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeModelArch(
    JNIEnv * env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) {
        return to_java_bytes(env, "");
    }
    char buf[128] = {};
    const int32_t len = llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf));
    if (len <= 0) {
        return to_java_bytes(env, "");
    }
    const size_t safe_len = std::min<size_t>(static_cast<size_t>(len), sizeof(buf) - 1);
    return to_java_bytes(env, std::string(buf, safe_len));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_legado_app_help_translate_HyMtNative_nativeIsPlamoTemplate(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr) {
        return JNI_FALSE;
    }
    const char * tmpl = llama_model_chat_template(g_model, nullptr);
    if (tmpl == nullptr || tmpl[0] == '\0') {
        return JNI_FALSE;
    }
    const std::string tmpl_str(tmpl);
    const bool is_plamo = tmpl_str.find("<|plamo:tag|>") != std::string::npos ||
                          tmpl_str.find("plamo-translate") != std::string::npos;
    return is_plamo ? JNI_TRUE : JNI_FALSE;
}

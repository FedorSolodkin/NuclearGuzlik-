#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <vector>
#include "llama.cpp/include/llama.h"

// Выбор слова для JSON-ответа («Выделить главное»): жадный выбор со штрафом за повторы и грамматикой формата.
//
// Старый способ (legacy): цепочка llama.cpp [штраф -> грамматика -> жадный выбор]. На каждом слове ответа и штраф, и грамматика
// обходят ВСЕ слова словаря (256 000 у Gemma): грамматика проверяет каждое слово, штраф ищет каждое в таблице. На слабых ядрах
// телефона это сотни миллисекунд на слово, хотя сам расчёт модели занимает меньше.
//
// Новый способ (lazy), результат тот же:
//   1. штраф за повторы применяется только к словам из последнего окна (до 64 слов), а не ко всему словарю;
//   2. выбирается лучшее слово простым проходом по оценкам;
//   3. грамматика проверяет ТОЛЬКО выбранное слово. Подошло — берём (так почти всегда).
//      Не подошло — старый путь: грамматика по всем словам и выбор лучшего из разрешённых.
// «Лучшее из разрешённых» совпадает с «лучшим», если лучшее разрешено; штраф меняет оценки одинаково в обоих способах.
// Так же устроен common_sampler_sample в llama.cpp. Один и тот же код проверяет тест на компьютере (research/gemma-speed/native_test).
namespace lazygrammar {

struct Sampler {
    // legacy
    llama_sampler*                chain   = nullptr;
    // lazy
    llama_sampler*                grammar = nullptr;
    std::deque<llama_token>       window;                 // последние выбранные слова для штрафа
    std::vector<llama_token_data> cand;                   // нужен только в редком запасном пути
    const bool                    lazy;
    const int                     last_n;
    const float                   penalty;
    long                          steps = 0, fallbacks = 0;
    bool                          ok = false;

    Sampler(const llama_vocab* vocab, const char* grammar_str, int penalty_last_n, float penalty_repeat, bool lazy_mode)
        : lazy(lazy_mode), last_n(penalty_last_n), penalty(penalty_repeat) {
        llama_sampler* g = llama_sampler_init_grammar(vocab, grammar_str, "root");
        if (!g) return;                                  // грамматика не разобралась: ok = false
        if (lazy) {
            grammar = g;
        } else {
            chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(chain, llama_sampler_init_penalties(penalty_last_n, penalty_repeat, 0.0f, 0.0f));
            llama_sampler_chain_add(chain, g);           // цепочка владеет грамматикой
            llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        }
        ok = true;
    }

    ~Sampler() {
        if (chain)   llama_sampler_free(chain);
        if (grammar) llama_sampler_free(grammar);
    }
    Sampler(const Sampler&)            = delete;
    Sampler& operator=(const Sampler&) = delete;

    // idx = -1: логиты последнего декодированного токена.
    llama_token sample(llama_context* ctx, const llama_vocab* vocab) {
        ++steps;
        if (!lazy) return llama_sampler_sample(chain, ctx, -1);

        float*    logits = llama_get_logits_ith(ctx, -1);
        const int n      = llama_vocab_n_tokens(vocab);

        // 1. Штраф за повторы только для слов из окна (как llama_sampler_penalties: делим положительные, умножаем отрицательные).
        if (penalty != 1.0f && !window.empty()) {
            std::vector<llama_token> distinct(window.begin(), window.end());
            std::sort(distinct.begin(), distinct.end());
            distinct.erase(std::unique(distinct.begin(), distinct.end()), distinct.end());
            for (llama_token t : distinct) {
                if (t < 0 || t >= n) continue;
                if (logits[t] <= 0.0f) logits[t] *= penalty; else logits[t] /= penalty;
            }
        }

        // 2. Лучшее слово (при равенстве первое по номеру, как в llama_sampler_greedy).
        int   best  = 0;
        float bestv = logits[0];
        for (int i = 1; i < n; ++i) if (logits[i] > bestv) { bestv = logits[i]; best = i; }
        llama_token tok = best;

        // 3. Грамматика проверяет только его.
        llama_token_data       one     = {tok, 1.0f, 0.0f};
        llama_token_data_array one_arr = {&one, 1, -1, false};
        llama_sampler_apply(grammar, &one_arr);
        if (one_arr.data[0].logit == -INFINITY) {
            ++fallbacks;                                 // лучшее слово запрещено форматом: старый путь
            cand.resize((size_t)n);
            for (int i = 0; i < n; ++i) cand[(size_t)i] = llama_token_data{i, logits[i], 0.0f};   // оценки уже со штрафом
            llama_token_data_array arr = {cand.data(), (size_t)n, -1, false};
            llama_sampler_apply(grammar, &arr);          // запрещённым словам ставится -inf
            int   b2  = 0;
            float bv2 = arr.data[0].logit;
            for (int i = 1; i < n; ++i) if (arr.data[i].logit > bv2) { bv2 = arr.data[i].logit; b2 = i; }
            tok = arr.data[b2].id;
        }
        llama_sampler_accept(grammar, tok);              // грамматика узнаёт о выбранном слове
        window.push_back(tok);
        if ((int)window.size() > last_n) window.pop_front();
        return tok;
    }
};

}  // namespace lazygrammar

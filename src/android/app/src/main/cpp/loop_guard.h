#pragma once
#include <vector>

// Детектор зацикливания генерации. Нужен, потому что дообученной Qwen мы отключаем штраф
// за повторы (он ломает JSON), а без штрафа модель теоретически может зациклиться.
//
// Срабатывает, когда хвост сгенерированных токенов — один и тот же блок из p токенов,
// повторённый подряд r раз, причём r >= 3 и p*r >= MIN_LOOP_TOKENS. Пороги выбраны так,
// чтобы не ловить законные повторы в JSON: кавычки, запятые и цифры («2000» — это цифры
// по отдельности) повторяются подряд максимум 3-4 раза, а до 24 одинаковых токенов подряд
// в нормальном ответе не бывает.
namespace loopguard {

constexpr int MIN_LOOP_TOKENS = 24;
constexpr int MAX_PERIOD      = 48;

template <typename T>
inline bool tail_is_loop(const std::vector<T>& g) {
    const int n = (int)g.size();
    for (int p = 1; p <= MAX_PERIOD; ++p) {
        int r = (MIN_LOOP_TOKENS + p - 1) / p;
        if (r < 3) r = 3;
        const int need = p * r;
        if (n < need) continue;          // need не монотонен по p — именно continue, не break
        bool periodic = true;
        for (int i = n - need; i + p < n; ++i) {
            if (g[i] != g[i + p]) { periodic = false; break; }
        }
        if (periodic) return true;
    }
    return false;
}

}  // namespace loopguard

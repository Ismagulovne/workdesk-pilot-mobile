package kz.workdesk.ai.pilot

/**
 * Правила безопасности (перенос policy.rs из wdpilot для ПК):
 * какие приложения нельзя выдавать ИИ и какой текст нельзя вводить.
 * Чистый Kotlin без Android — проверяется юнит-тестами.
 */
object Policy {
    /** Пакеты, которые никогда не выдаются ИИ. */
    val DENY_PACKAGES = setOf(
        // сами себе и Claude
        "kz.workdesk.ai.pilot", "kz.workdesk.ai", "com.anthropic.claude",
        // системные настройки, установка приложений, магазин (покупки), разрешения, экран блокировки
        "com.android.settings", "com.android.vending", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.systemui",
        // менеджеры паролей и аутентификаторы
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid",
        "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.dashlane",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "kz.worktime.authenticator",
        // кошельки
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay",
    )

    /** Слова в пакете или названии: банки, платежи, пароли. */
    val DENY_WORDS = listOf(
        "kaspi", "halyk", "homebank", "jusan", "freedom", "forte", "bcc", "sber", "otbasy", "eubank",
        "bank", "банк", "wallet", "кошел", "pay", "password", "пароль", "парол", "authenticator", "vault",
    )

    /**
     * Удалённые рабочие столы: их экран — картинка без дерева элементов, поэтому проверку боевой базы 1С
     * по тексту сделать нельзя. Пока на мосту нет распознавания текста снимка (OCR), такие клиенты запрещены.
     */
    val REMOTE_DESKTOP_PACKAGES = setOf(
        "com.microsoft.rdc.androidx", "com.microsoft.rdc.android", "kz.workdesk.app", "com.carriez.flutter_hbb",
        "com.anydesk.anydeskandroid", "com.teamviewer.teamviewer.market.mobile", "com.realvnc.viewer.android",
    )

    /**
     * Список запретов пользователя (например, «ТОО ФРК РОЗНИЦА» — боевая база 1С). Видеть цель на экране можно;
     * действие, которое её открывает, выполняется только после «Да» человека. Заполняет и правит только человек
     * на главном экране приложения; инструмента для правки у ИИ нет. По умолчанию список пуст.
     */
    fun norm(s: String) = s.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[\"«»“”„'`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Первая цель из списка, которая встречается в текстах; null — нет. */
    fun findBlocked(targets: Collection<String>, texts: Iterable<String>): String? {
        val pats = targets.map { Pair(it.trim(), norm(it)) }.filter { it.second.isNotEmpty() }
        if (pats.isEmpty()) return null
        for (t in texts) {
            val n = norm(t)
            pats.firstOrNull { n.contains(it.second) }?.let { return it.first }
        }
        return null
    }

    /** null — можно; иначе причина отказа. */
    fun appAllowed(pkg: String, label: String): String? {
        if (pkg in REMOTE_DESKTOP_PACKAGES) {
            return "«$label» — удалённый рабочий стол. Пока нет распознавания текста снимка, ИИ его не выдаётся " +
                "(нельзя гарантировать, что не откроется боевая база 1С)"
        }
        if (pkg in DENY_PACKAGES) {
            return "приложение «$label» запрещено правилами (системные настройки, магазин, пароли, кошельки и сам Claude ИИ не выдаются)"
        }
        val hay = (pkg + " " + label).lowercase()
        // «pay» ищем только как отдельную часть пакета или слова, чтобы не задеть «display», «player».
        val words = hay.split('.', ' ', '_', '-')
        for (w in DENY_WORDS) {
            val hit = if (w == "pay") words.any { it == "pay" || it.endsWith("pay") && it.length <= 8 } else hay.contains(w)
            if (hit) return "приложение «$label» запрещено правилами (совпадение «$w»): банки, платежи и пароли ИИ не выдаются"
        }
        return null
    }

    /** Нельзя вводить номера банковских карт и строки, похожие на токены. */
    fun textAllowed(text: String): String? {
        if (hasCardNumber(text)) return "ввод запрещён: в тексте номер банковской карты. Платёжные данные вводит человек."
        val prefixes = listOf("sk-ant-", "sk-", "ghp_", "github_pat_", "gho_", "xoxb-", "xoxp-", "akia", "aiza", "-----begin")
        for (w in text.split(Regex("\\s+"))) {
            val lw = w.lowercase()
            if (w.length >= 20 && prefixes.any { lw.startsWith(it) }) {
                return "ввод запрещён: текст похож на токен или ключ доступа. Секреты вводит человек."
            }
        }
        return null
    }

    fun hasCardNumber(text: String): Boolean {
        val digits = ArrayList<Int>()
        fun flush(): Boolean {
            val ok = digits.size in 13..19 && luhn(digits)
            digits.clear()
            return ok
        }
        val cs = text.toCharArray()
        for (i in cs.indices) {
            val c = cs[i]
            when {
                c.isDigit() -> digits.add(c - '0')
                (c == ' ' || c == '-') && digits.isNotEmpty() && i + 1 < cs.size && cs[i + 1].isDigit() -> {}
                else -> if (flush()) return true
            }
        }
        return flush()
    }

    fun luhn(d: List<Int>): Boolean {
        var sum = 0
        for ((i, v) in d.reversed().withIndex()) {
            var x = v
            if (i % 2 == 1) { x *= 2; if (x > 9) x -= 9 }
            sum += x
        }
        return sum % 10 == 0
    }
}

/** Геометрия снимка: какой размер отдаём модели и как переводим координаты обратно. */
object Geometry {
    const val LONG_EDGE = 1280
    const val MAX_PIXELS = 1_150_000

    fun fit(w: Int, h: Int, longEdge: Int = LONG_EDGE, maxPx: Int = MAX_PIXELS): Pair<Int, Int> {
        val wf = maxOf(w, 1).toDouble()
        val hf = maxOf(h, 1).toDouble()
        val s = minOf(1.0, longEdge / maxOf(wf, hf), Math.sqrt(maxPx / (wf * hf)))
        return Pair(maxOf(1, Math.round(wf * s).toInt()), maxOf(1, Math.round(hf * s).toInt()))
    }

    /** Точка снимка → точка экрана. */
    fun toScreen(x: Double, y: Double, left: Int, top: Int, scale: Double): Pair<Float, Float> =
        Pair((left + x / scale).toFloat(), (top + y / scale).toFloat())
}

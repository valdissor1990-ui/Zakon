package ru.zakon.app

/** Presentation metadata only. Legal explanations and normative text are delivered in the content database. */

data class CodePart(val id: String, val title: String)
data class LiabilityItem(val title: String, val body: String)
data class RfCode(
    val id: String,
    val shortName: String,
    val fullName: String,
    val symbol: String,
    val parts: List<CodePart> = emptyList(),
    val hasLiability: Boolean = false,
    val liabilityTitle: String = "Штрафы и ответственность",
    val overview: String = "",
    val liabilityItems: List<LiabilityItem> = emptyList()
)

val AllRfCodes: List<RfCode> = listOf(
    RfCode(
        id = "gk", shortName = "ГК РФ", fullName = "Гражданский кодекс Российской Федерации",
        symbol = "Гк", parts = listOf(
            CodePart("gk1", "Часть первая"), CodePart("gk2", "Часть вторая"),
            CodePart("gk3", "Часть третья"), CodePart("gk4", "Часть четвёртая")
        ),
    ),
    RfCode(
        id = "uk", shortName = "УК РФ", fullName = "Уголовный кодекс Российской Федерации",
        symbol = "Ук", hasLiability = true,
    ),
    RfCode(
        id = "upk", shortName = "УПК РФ", fullName = "Уголовно-процессуальный кодекс Российской Федерации",
        symbol = "Уп",
    ),
    RfCode(
        id = "gpk", shortName = "ГПК РФ", fullName = "Гражданский процессуальный кодекс Российской Федерации",
        symbol = "Гп",
    ),
    RfCode(
        id = "apk", shortName = "АПК РФ", fullName = "Арбитражный процессуальный кодекс Российской Федерации",
        symbol = "Ап",
    ),
    RfCode(
        id = "koap", shortName = "КоАП РФ", fullName = "Кодекс Российской Федерации об административных правонарушениях",
        symbol = "Ко",
        hasLiability = true,
    ),
    RfCode(
        id = "tk", shortName = "ТК РФ", fullName = "Трудовой кодекс Российской Федерации",
        symbol = "Тк", hasLiability = true,
    ),
    RfCode(
        id = "sk", shortName = "СК РФ", fullName = "Семейный кодекс Российской Федерации",
        symbol = "Ск",
    ),
    RfCode(
        id = "zhk", shortName = "ЖК РФ", fullName = "Жилищный кодекс Российской Федерации",
        symbol = "Жк",
    ),
    RfCode(
        id = "zk", shortName = "ЗК РФ", fullName = "Земельный кодекс Российской Федерации",
        symbol = "Зк",
    ),
    RfCode(
        id = "grk", shortName = "ГрК РФ", fullName = "Градостроительный кодекс Российской Федерации",
        symbol = "Гр",
    ),
    RfCode(
        id = "nk", shortName = "НК РФ", fullName = "Налоговый кодекс Российской Федерации",
        symbol = "Нк",
        parts = listOf(CodePart("nk1", "Часть первая"), CodePart("nk2", "Часть вторая")),
        hasLiability = true,
    ),
    RfCode(
        id = "bk", shortName = "БК РФ", fullName = "Бюджетный кодекс Российской Федерации",
        symbol = "Бк",
    ),
    RfCode(
        id = "vk", shortName = "ВК РФ", fullName = "Водный кодекс Российской Федерации",
        symbol = "Вд",
    ),
    RfCode(
        id = "lk", shortName = "ЛК РФ", fullName = "Лесной кодекс Российской Федерации",
        symbol = "Лс",
    ),
    RfCode(
        id = "air", shortName = "Воздушный кодекс", fullName = "Воздушный кодекс Российской Федерации",
        symbol = "Вз",
    ),
    RfCode(
        id = "ktm", shortName = "КТМ РФ", fullName = "Кодекс торгового мореплавания Российской Федерации",
        symbol = "Мр",
    ),
    RfCode(
        id = "kas", shortName = "КАС РФ", fullName = "Кодекс административного судопроизводства Российской Федерации",
        symbol = "Ас",
    ),
    RfCode(
        id = "uik", shortName = "УИК РФ", fullName = "Уголовно-исполнительный кодекс Российской Федерации",
        symbol = "Уи",
    )
)

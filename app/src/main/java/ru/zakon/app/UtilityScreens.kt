package ru.zakon.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val GlassBorder = Color.White.copy(alpha = 0.35f)

// ─── Cabinet (local-only cases, password gate) ──────────────────────────────

private data class LocalCase(
    val id: String,
    val title: String,
    val notes: String,
    val updatedAt: Long
)

private class CabinetStorage(context: Context) {
    private val prefs = context.getSharedPreferences("zakon_cabinet", Context.MODE_PRIVATE)

    fun hasPassword(): Boolean = prefs.contains("pin_hash")
    fun setPassword(pin: String) {
        prefs.edit().putString("pin_hash", pin.hashCode().toString()).apply()
    }
    fun checkPassword(pin: String): Boolean =
        prefs.getString("pin_hash", null) == pin.hashCode().toString()

    fun loadCases(): List<LocalCase> {
        val raw = prefs.getString("cases_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(LocalCase(
                        o.getString("id"),
                        o.getString("title"),
                        o.optString("notes", ""),
                        o.optLong("updatedAt", 0L)
                    ))
                }
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun saveCases(cases: List<LocalCase>) {
        val arr = JSONArray()
        cases.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("notes", c.notes)
                put("updatedAt", c.updatedAt)
            })
        }
        prefs.edit().putString("cases_json", arr.toString()).apply()
    }
}

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val storage = remember { CabinetStorage(context) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf(storage.loadCases()) }
    var editing by remember { mutableStateOf<LocalCase?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Личный кабинет", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (storage.hasPassword()) "Введите пароль для доступа"
                    else "Задайте пароль для защиты данных на устройстве",
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12) pin = it },
                    label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = cabinetFieldColors(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!storage.hasPassword()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = { if (it.length <= 12) pinConfirm = it },
                        label = { Text("Повтор пароля") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = cabinetFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        if (pin.length < 4) {
                            error = "Минимум 4 символа"
                            return@Button
                        }
                        if (!storage.hasPassword()) {
                            if (pin != pinConfirm) {
                                error = "Пароли не совпадают"
                                return@Button
                            }
                            storage.setPassword(pin)
                            unlocked = true
                        } else if (storage.checkPassword(pin)) {
                            unlocked = true
                        } else {
                            error = "Неверный пароль"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (storage.hasPassword()) "Войти" else "Создать пароль и войти", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Биометрия будет доступна после первого входа по паролю (настройка в следующих версиях).\nВсе данные хранятся только на этом устройстве.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (editing != null) {
            Text("Дело / материал", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                label = { Text("Название") },
                colors = cabinetFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = editNotes,
                onValueChange = { editNotes = it },
                label = { Text("Заметки, материалы, ссылки") },
                minLines = 8,
                colors = cabinetFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { editing = null },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) { Text("Отмена") }
                Button(
                    onClick = {
                        val id = editing!!.id
                        val updated = LocalCase(id, editTitle.ifBlank { "Без названия" }, editNotes, System.currentTimeMillis())
                        cases = if (cases.any { it.id == id }) cases.map { if (it.id == id) updated else it }
                        else listOf(updated) + cases
                        storage.saveCases(cases)
                        editing = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy),
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Личный кабинет", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Только на этом устройстве", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    editing = LocalCase(UUID.randomUUID().toString(), "", "", 0L)
                    editTitle = ""
                    editNotes = ""
                }, colors = ButtonDefaults.textButtonColors(contentColor = LegalGold)) {
                    Text("+ Дело")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (cases.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Пока нет дел.\nСоздайте дело, чтобы вести заметки и материалы.\nФото и вложения — в следующих обновлениях.",
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cases, key = { it.id }) { c ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(0.18f), Color.White.copy(0.08f))
                                    ),
                                    RoundedCornerShape(16.dp)
                                )
                                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                .clickable {
                                    editing = c
                                    editTitle = c.title
                                    editNotes = c.notes
                                }
                                .padding(14.dp)
                        ) {
                            Column {
                                Text(c.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                if (c.notes.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        c.notes.take(120) + if (c.notes.length > 120) "…" else "",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cabinetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = LegalGold,
    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
    focusedLabelColor = LegalGold,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
    cursorColor = LegalGold
)

// ─── About ──────────────────────────────────────────────────────────────────

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("О приложении", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.1.0", color = LegalGold, fontWeight = FontWeight.SemiBold)

        SectionTitle("Назначение")
        BodyText(
            "Приложение «ЗаконЪ» — справочная офлайн-библиотека нормативных материалов Российской Федерации. " +
            "Предназначено для удобного ознакомления с текстами кодексов, законов и отдельных статей. " +
            "Приложение не является государственной информационной системой и не заменяет официальные источники опубликования."
        )

        SectionTitle("Правовая оговорка")
        BodyText(
            "Создатель приложения Исаев Владислав Андреевич не несёт ответственности за полноту, актуальность и точность размещённых материалов. " +
            "Материалы собраны из открытых источников и носят исключительно справочный характер. " +
            "Информация не является юридической консультацией, официальным разъяснением и не может служить единственным основанием для принятия юридически значимых решений.\n\n" +
            "Для совершения юридически значимых действий рекомендуется обращаться к актуальным официальным публикациям (в том числе на Официальном интернет-портале правовой информации pravo.gov.ru) и при необходимости к квалифицированным специалистам.\n\n" +
            "Использование приложения осуществляется пользователем самостоятельно и на свой риск в пределах, допускаемых законодательством Российской Федерации."
        )

        SectionTitle("Персональные данные и локальное хранение")
        BodyText(
            "Данные личного кабинета (заметки, дела, материалы) хранятся только на устройстве пользователя и не передаются разработчику. " +
            "Разработчик не осуществляет сбор биометрических данных через настоящее приложение."
        )

        SectionTitle("Технологии и лицензии")
        BodyText(
            "Приложение собрано на платформе Android с использованием Kotlin и Jetpack Compose (AndroidX).\n" +
            "• Kotlin — Apache License 2.0 (jetbrains.com)\n" +
            "• Android Jetpack / Compose — Apache License 2.0 (developer.android.com)\n" +
            "• Фоновые изображения и геральдические элементы — используются на условиях соответствующих лицензий источников (в том числе материалы, совместимые с свободным использованием при соблюдении условий правообладателей).\n\n" +
            "Исходный код приложения разрабатывается владельцем проекта. Сторонние зависимости распространяются на условиях их собственных лицензий."
        )

        SectionTitle("Сотрудничество")
        BodyText("По вопросам сотрудничества: Valdissor@mail.ru")

        SectionTitle("Автор")
        BodyText("Исаев Владислав Андреевич")

        Text(
            "© Использование материалов приложения не освобождает от обязанности соблюдать законодательство РФ.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LegalGold, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
internal fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Инструменты", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Surface(
            Modifier.fillMaxWidth().clickable(onClick = onConstructor),
            color = Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("§", color = LegalGold, fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Конструктор документов", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Локальный черновик", color = Color.White.copy(0.65f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    var documentName by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Конструктор документов", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "Локальный редактор-заготовка. Не является юридической консультацией.",
            color = Color.White.copy(0.65f),
            fontSize = 13.sp
        )
        OutlinedTextField(documentName, { documentName = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp))
        OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text("Кому") }, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp))
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("Текст") }, minLines = 6, colors = cabinetFieldColors(), shape = RoundedCornerShape(12.dp))
        Button(onClick = { preview = !preview }, colors = ButtonDefaults.buttonColors(containerColor = LegalGold, contentColor = LegalNavy)) {
            Text(if (preview) "Скрыть просмотр" else "Просмотр")
        }
        if (preview) {
            Surface(color = Color.White.copy(0.12f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, GlassBorder)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(documentName.ifBlank { "Название" }, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(recipient.ifBlank { "Адресат" }, color = Color.White.copy(0.7f))
                    HorizontalDivider(color = Color.White.copy(0.2f))
                    Text(body.ifBlank { "Текст" }, color = Color.White.copy(0.9f))
                }
            }
        }
    }
}

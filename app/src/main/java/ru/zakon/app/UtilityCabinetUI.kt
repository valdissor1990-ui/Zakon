package ru.zakon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val OnCab = Color(0xFF1A2332)
private val MutedGray = Color(0xFF5A6577)
private val LineGray = Color(0xFFD0D7E2)
private val AccentGold = Color(0xFF8B6914)
private val SoftBg = Color(0xFFF3F5F8)

@Composable internal fun HubUI(ws: CabinetWorkspace, hasPw: Boolean, canBio: Boolean, onClients: () -> Unit, onCases: () -> Unit, onCase: (String) -> Unit, onLock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Личный кабинет", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(when {
                    hasPw && canBio -> "Пароль и биометрия · только на устройстве"
                    hasPw -> "Защищён паролем · только на устройстве"
                    else -> "Без пароля · только на устройстве"
                }, color = MutedGray, fontSize = 12.sp)
            }
            if (hasPw) TextButton(onClick = onLock) { Text("Выйти", color = MutedGray) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Клиенты", "${ws.clients.size}", Modifier.weight(1f), onClients)
            Stat("Дела", "${ws.cases.size}", Modifier.weight(1f), onCases)
            Stat("Задачи", "${ws.tasks.count { it.status != "done" }}", Modifier.weight(1f), onCases)
        }
        Spacer(Modifier.height(12.dp))
        Text("Разделы", color = AccentGold, fontWeight = FontWeight.SemiBold)
        MenuRow("Клиенты", "Карточки и контакты", onClients)
        MenuRow("Дела", "Хронология, документы, задачи", onCases)
        Spacer(Modifier.height(12.dp))
        Text("Недавние дела", color = AccentGold, fontWeight = FontWeight.SemiBold)
        if (ws.cases.isEmpty()) Text("Создайте клиента и дело", color = MutedGray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ws.cases.take(8), key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable { onCase(c.id) }.padding(12.dp)) {
                    Text(c.title, color = OnCab, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun Stat(t: String, v: String, m: Modifier, onClick: () -> Unit) =
    Column(m.border(1.dp, LineGray, RoundedCornerShape(12.dp)).background(SoftBg, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(t, color = MutedGray, fontSize = 11.sp)
    }

@Composable private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnCab, fontWeight = FontWeight.SemiBold)
            Text(sub, color = MutedGray, fontSize = 12.sp)
        }
        Text("›", color = AccentGold, fontSize = 20.sp)
    }
}

@Composable internal fun SimpleList(title: String, items: List<Pair<String, String>>, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(title, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("+ Добавить", color = AccentGold) }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) Text("Пусто", color = MutedGray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                Row(Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable { onOpen(id) }.padding(14.dp)) {
                    Text(label, color = OnCab, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("›", color = AccentGold)
                }
            }
        }
    }
}

@Composable internal fun ClientForm(existing: ClientCard?, onBack: () -> Unit, onSave: (ClientCard) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
        Text(if (existing == null) "Новый клиент" else "Клиент", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray, focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray))
        OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray, focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray))
        OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray, focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray))
        Button(onClick = {
            if (name.isBlank()) return@Button
            onSave(ClientCard(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), phone = phone.trim(), note = note.trim()))
        }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
        if (existing != null) TextButton(onClick = { onDelete(existing.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
    }
}

@Composable internal fun CaseForm(existing: CaseCard?, clients: List<ClientCard>, onBack: () -> Unit, onSave: (CaseCard) -> Unit, onDelete: (String) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var status by remember { mutableStateOf(existing?.status ?: CaseStatuses.first().first) }
    var category by remember { mutableStateOf(existing?.category ?: CaseCategories.first().first) }
    var clientId by remember { mutableStateOf(existing?.clientId ?: clients.firstOrNull()?.id ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
        Text(if (existing == null) "Новое дело" else "Дело", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = OnCab, unfocusedTextColor = OnCab, focusedBorderColor = AccentGold, unfocusedBorderColor = LineGray, focusedLabelColor = AccentGold, unfocusedLabelColor = MutedGray))
        Text("Статус", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaseStatuses.forEach { (id, label) -> FilterChip(selected = status == id, onClick = { status = id }, label = { Text(label) }) }
        }
        Text("Категория", color = MutedGray, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaseCategories.forEach { (id, label) -> FilterChip(selected = category == id, onClick = { category = id }, label = { Text(label) }) }
        }
        if (clients.isNotEmpty()) {
            Text("Клиент", color = MutedGray, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                clients.forEach { c -> FilterChip(selected = clientId == c.id, onClick = { clientId = c.id }, label = { Text(c.name) }) }
            }
        }
        Button(onClick = {
            if (title.isBlank()) return@Button
            onSave(CaseCard(id = existing?.id ?: UUID.randomUUID().toString(), title = title.trim(), status = status, category = category, clientId = clientId))
        }, colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Сохранить") }
        if (existing != null) TextButton(onClick = { onDelete(existing.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
    }
}

@Composable internal fun CaseView(ws: CabinetWorkspace, caseId: String, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit, onEdit: () -> Unit, st: CabinetStorage, scope: kotlinx.coroutines.CoroutineScope, busy: Boolean, onBusy: (Boolean) -> Unit, onErr: (String?) -> Unit, onSaveWs: (CabinetWorkspace) -> Unit) {
    val c = ws.cases.find { it.id == caseId }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(c?.title ?: "Дело", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onEdit) { Text("Изменить", color = AccentGold) }
        }
        if (c == null) Text("Не найдено", color = MutedGray)
        else {
            Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Хронология, документы и задачи — в следующих обновлениях.", color = MutedGray, fontSize = 13.sp)
        }
    }
}

internal fun lab(list: List<Pair<String, String>>, id: String) = list.find { it.first == id }?.second ?: id

internal val CaseStatuses = listOf("open" to "Открыто", "work" to "В работе", "done" to "Закрыто", "pause" to "Пауза")
internal val CaseCategories = listOf("civil" to "Гражданское", "admin" to "Административное", "criminal" to "Уголовное", "other" to "Иное")

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("О приложении", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("ЗаконЪ · версия 0.2.0", color = AccentGold, fontWeight = FontWeight.SemiBold)
        Text("Офлайн-справочник кодексов и законов РФ. Личный кабинет хранит данные только на устройстве.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Конструктор документов", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Шаблоны заявлений появятся в следующих обновлениях.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

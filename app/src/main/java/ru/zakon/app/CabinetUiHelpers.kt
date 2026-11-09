package ru.zakon.app

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

@Composable
internal fun HubUI(
    ws: CabinetWorkspace,
    hasPw: Boolean,
    canBio: Boolean,
    onClients: () -> Unit,
    onCases: () -> Unit,
    onCase: (String) -> Unit,
    onLock: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Личный кабинет", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    if (hasPw && canBio) "Пароль и биометрия · только на устройстве"
                    else if (hasPw) "Защищён паролем · только на устройстве"
                    else "Требуется пароль · только на устройстве",
                    color = MutedGray, fontSize = 12.sp
                )
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
                Column(
                    Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp))
                        .clickable { onCase(c.id) }.padding(12.dp)
                ) {
                    Text(c.title, color = OnCab, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Stat(t: String, v: String, m: Modifier, onClick: () -> Unit) =
    Column(
        m.border(1.dp, LineGray, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp)
    ) {
        Text(v, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(t, color = MutedGray, fontSize = 12.sp)
    }

@Composable
private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, LineGray, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnCab, fontWeight = FontWeight.SemiBold)
            Text(sub, color = MutedGray, fontSize = 12.sp)
        }
        Text("›", color = AccentGold, fontSize = 22.sp)
    }
}

@Composable
internal fun SimpleList(
    title: String,
    items: List<Pair<String, String>>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(title, color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("+", color = AccentGold, fontSize = 22.sp) }
        }
        if (items.isEmpty()) Text("Пусто", color = MutedGray, modifier = Modifier.padding(top = 24.dp))
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(items, key = { it.first }) { (id, label) ->
                Text(
                    label,
                    color = OnCab,
                    modifier = Modifier.fillMaxWidth().border(1.dp, LineGray, RoundedCornerShape(12.dp))
                        .clickable { onOpen(id) }.padding(12.dp)
                )
            }
        }
    }
}

@Composable
internal fun ClientForm(
    initial: CabClient?,
    onBack: () -> Unit,
    onSave: (CabClient) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (initial == null) "Новый клиент" else "Клиент", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (name.isBlank()) return@Button
                onSave(
                    CabClient(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        notes = notes.trim()
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        TextButton(onClick = onBack) { Text("Отмена", color = MutedGray) }
        if (initial != null) {
            TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
        }
    }
}

@Composable
internal fun CaseForm(
    initial: CabCase?,
    clients: List<CabClient>,
    onBack: () -> Unit,
    onSave: (CabCase) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var subject by remember { mutableStateOf(initial?.subject ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var status by remember { mutableStateOf(initial?.status ?: "active") }
    var category by remember { mutableStateOf(initial?.category ?: "civil") }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (initial == null) "Новое дело" else "Дело", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(subject, { subject = it }, label = { Text("Предмет") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Заметки") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (title.isBlank()) return@Button
                onSave(
                    CabCase(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        title = title.trim(),
                        category = category,
                        status = status,
                        subject = subject.trim(),
                        notes = notes.trim()
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        TextButton(onClick = onBack) { Text("Отмена", color = MutedGray) }
        if (initial != null) {
            TextButton(onClick = { onDelete(initial.id) }) { Text("Удалить", color = Color(0xFFC62828)) }
        }
    }
}

@Composable
internal fun CaseView(
    ws: CabinetWorkspace,
    caseId: String,
    tab: Int,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    st: CabinetStorage,
    scope: kotlinx.coroutines.CoroutineScope,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onErr: (String?) -> Unit,
    onSaveWs: (CabinetWorkspace) -> Unit
) {
    val c = ws.cases.find { it.id == caseId }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AccentGold) }
            Text(c?.title ?: "Дело", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onEdit) { Text("Изменить", color = AccentGold) }
        }
        if (c == null) {
            Text("Дело не найдено", color = MutedGray)
            return
        }
        Text("${lab(CaseCategories, c.category)} · ${lab(CaseStatuses, c.status)}", color = MutedGray, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (c.subject.isNotBlank()) Text(c.subject, color = OnCab)
        if (c.notes.isNotBlank()) Text(c.notes, color = MutedGray, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Text("События: ${ws.events.count { it.caseId == caseId }} · Документы: ${ws.docs.count { it.caseId == caseId }} · Задачи: ${ws.tasks.count { it.caseId == caseId }}", color = MutedGray, fontSize = 12.sp)
    }
}

@Composable
internal fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("О приложении", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("ЗаконЪ — офлайн-справочник кодексов РФ и личный кабинет для дел.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
        Text("Данные кабинета хранятся только на устройстве и защищены паролем.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
        Text("Тексты носят справочный характер. Сверяйте с официальными источниками.", color = MutedGray, fontSize = 13.sp, lineHeight = 18.sp)
        Text("Версия 0.1 offline", color = AccentGold, fontSize = 12.sp)
    }
}

@Composable
internal fun ConstructorScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Конструктор документов", color = OnCab, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("Шаблоны заявлений появятся в следующих обновлениях.", color = MutedGray, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

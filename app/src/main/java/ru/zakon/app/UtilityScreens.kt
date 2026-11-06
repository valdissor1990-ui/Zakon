package ru.zakon.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CabinetScreen(padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Личный кабинет", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Ваше рабочее пространство на устройстве", color = LegalMuted)
        CabinetTile("Закладки", "Сохранённые статьи и документы", "☆")
        CabinetTile("История", "Недавно открытые материалы", "◷")
        CabinetTile("Мои материалы", "Черновики и подготовленные документы", "▤")
        Surface(color = LegalSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LegalLine)) {
            Text(
                "Локальные профили и синхронизация пока не подключены. Данные этого раздела не отправляются на сервер.",
                Modifier.padding(16.dp), color = LegalMuted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CabinetTile(title: String, subtitle: String, symbol: String) {
    Surface(color = LegalSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = LegalGold, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = LegalMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun MoreScreen(padding: PaddingValues, onConstructor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Инструменты и настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(
            Modifier.fillMaxWidth().clickable(onClick = onConstructor),
            color = LegalSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LegalGold)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✎", color = LegalGold, fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Конструктор документов", fontWeight = FontWeight.Bold)
                    Text("Подготовка собственного черновика", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("ЗаконЪ · версия 0.1.0")
        Text("Правовая база, обновления и проверка редакций будут доступны после подключения подписанных выпусков.")
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
        Text("Конструктор документов", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Создайте черновик. Это локальный редактор-заготовка, не юридическая консультация и не готовый процессуальный документ.", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(documentName, { documentName = it }, Modifier.fillMaxWidth(), label = { Text("Название документа") }, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text("Кому адресован") }, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("Текст и обстоятельства") }, minLines = 6, shape = RoundedCornerShape(12.dp))
        Button(onClick = { preview = !preview }, colors = ButtonDefaults.buttonColors(containerColor = LegalNavy)) {
            Text(if (preview) "Скрыть предварительный просмотр" else "Предварительный просмотр")
        }
        if (preview) {
            Surface(color = LegalSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LegalGold.copy(alpha = 0.7f))) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(documentName.ifBlank { "Название документа" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(recipient.ifBlank { "Адресат" }, color = LegalMuted)
                    HorizontalDivider()
                    Text(body.ifBlank { "Текст документа" }, style = MaterialTheme.typography.bodyMedium)
                    Text("ЧЕРНОВИК", color = LegalGold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Сохранение, экспорт в PDF/DOCX и шаблоны документов будут добавлены отдельным этапом.", color = LegalMuted, style = MaterialTheme.typography.bodySmall)
    }
}

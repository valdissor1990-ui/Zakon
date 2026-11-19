# Агент подготовки правовой базы

## Полная автоматизация (без ручного участия)

Оркестратор `agent/auto_fill.py` и workflow **Content auto-fill**:

1. Capture официальных URL из `content/intake/source-requests.json`
2. Extract DOCX/RTF → `content/extracted/`
3. Auto article-index по заголовкам «Статья N»
4. Сборка `content/records/records.json` (single-source, auto-approved)
5. Draft SQLite в `dist/content/`

### Запуск локально

```bash
cd agent
# только Минюст (без прокси)
python3 auto_fill.py --skip-capture   # если evidence уже есть
python3 auto_fill.py                  # полный цикл с сетью

# с ротацией русских прокси (для pravo.gov.ru)
python3 auto_fill.py --proxy-file proxies.txt
```

### GitHub Actions

- Workflow: `.github/workflows/content-auto-fill.yml`
- Расписание: ежедневно 03:15 UTC
- Ветка: `content/single-source-fill`
- Ручной запуск: Actions → Content auto-fill → Run workflow

**Секрет (опционально):** `LEGAL_PROXY_LIST`  
Список прокси по одному на строку (как в `proxies.example.txt`). Без него pravo.gov.ru с runner’а почти всегда timeout; Минюст всё равно наполняется.

Публикация в production по-прежнему через `content-release.yml` + environment `legal-content-approval` (подпись, tag `content-*`).

### Что заполняется автоматически сейчас

| source_key | Документ |
|------------|----------|
| advocacy_minjust_rtf | 63-ФЗ |
| constitution_minjust_rtf | Конституция РФ |
| uk_minjust_docx | УК РФ (редакция файла Минюста) |
| gpk_minjust_docx | ГПК РФ |
| koap_minjust_docx | КоАП РФ |
| kas_minjust_docx | КАС РФ |

DOCX Минюста могут отставать от текущей редакции закона — в `source_revision` это отмечено. Актуальные тексты с pravo требуют `LEGAL_PROXY_LIST`.

---

## Жёсткие правила

- Не генерировать отсутствующий текст нормы.
- Сохранять URL, время получения, SHA-256 исходного файла.
- Single-source policy: достаточно одного государственного источника.

## Ротация прокси

См. `adapters/proxy_pool.py`, `--proxy-file`, `PROXY_LIST`, `LEGAL_PROXY_LIST`.

```bash
python3 capture_sources.py --proxy-file proxies.txt \
  --records ../content/intake/source-requests.json
```

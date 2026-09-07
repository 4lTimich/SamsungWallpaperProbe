# Iridescent Wallpaper Lab v0.7 — GPU + Real One UI Offset Probe

Две большие перемены:

1. **Canvas-рендер живых обоев удалён.** Wallpaper теперь рисуется через EGL/OpenGL ES 2.0 на отдельном render thread. Фон — один fullscreen fragment shader, стекло — дешёвые GPU quads. Цель — нормальные 50–60 FPS вместо 5–15 FPS.
2. **Экспериментальный реальный offset One UI.** В тот же APK добавлена opt-in AccessibilityService `One UI Offset Probe`, ограниченная пакетом `com.sec.android.app.launcher` и без `canRetrieveWindowContent`. Если One UI Home отправляет `TYPE_VIEW_SCROLLED` с нормальными `scrollX/maxScrollX`, wallpaper использует этот реальный скролл вместо виртуального угадывания по пальцу.

## Как включить real offset

После установки v0.7 открой приложение → нажми `ВКЛЮЧИТЬ РЕАЛЬНЫЙ OFFSET ONE UI` → в системном списке специальных возможностей включи `One UI Offset Probe` → вернись на рабочий стол.

У приложения нет INTERNET permission. XML службы фильтрует accessibility-события только на `com.sec.android.app.launcher`; содержимое окон не запрашивается.

## Что смотреть в отладке

Включи `ОТЛАДКА` в приложении. На wallpaper появятся строки:

- `FPS` — фактическая частота GPU-рендера;
- `SOURCE A11Y REAL` — One UI реально отдаёт абсолютный `scrollX/maxScrollX`; это лучший исход;
- `SOURCE A11Y PAGE` — One UI отдаёт только индекс страницы; этого хватает хотя бы для точной фиксации результата свайпа;
- `SOURCE TOUCH` — accessibility не дала пригодного offset, используется старый fallback;
- `A11Y x=... max=... dx=...` — сырые поля One UI для диагностики;
- `idx from>to count` — индексы из AccessibilityEvent;
- класс события и короткое системное описание.

## Важный тест

Сделай медленный свайп вперёд, не отпуская палец верни его назад и отпусти. Если `SOURCE A11Y REAL`, стекло должно повторить реальную страницу и вернуться вместе с One UI, а не самостоятельно «решить», что переход состоялся.

Если `A11Y REAL` не появится, пришли скрин отладки после нескольких свайпов. По `x/max/dx/from/to/count/class` будет понятно, можно ли извлечь реальную страницу из другого accessibility-поля.

Редактор сетки/ячеек и назначения приложений из v0.6 сохранён.

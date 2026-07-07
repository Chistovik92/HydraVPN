gradle-wrapper.jar здесь намеренно отсутствует (бинарник).
Сгенерируйте wrapper одной командой (нужен установленный Gradle 8.9+):

    gradle wrapper --gradle-version 8.9

Либо просто откройте проект в Android Studio — она восстановит wrapper автоматически.

#ifndef CIADPI_H
#define CIADPI_H

/// Запуск ByeDPI (`ciadpi`, MIT, github.com/hufrea/byedpi v0.17.3) внутри процесса. Блокирует поток до [ciadpi_stop].
/// Состояние сбрасывается перед запуском, поэтому подряд можно запускать разные стратегии (мастер подбора).
int ciadpi_run(int argc, char **argv);

/// Остановить сервер: закрывает слушающий сокет, ciadpi_run возвращается.
void ciadpi_stop(void);

#endif

// Package hydraflux — клиент OpenFlux для iOS (0.7.10).
//
// Тот же приём, что hydraolc: OpenFlux работает внутри расширения VPN как библиотека (общая
// gomobile-сборка с libbox, ios/Bridge/build.sh) и открывает локальный SOCKS5, а sing-box ходит
// к нему мостом. Обёртка нужна, потому что пакет апстрима называется `mobile`, как и у olcRTC.
package hydraflux

import (
	"errors"
	"fmt"

	flux "openflux-mobile"
)

// Start поднимает клиент. transport: yandex | vyandex | oneme | cupsonline | mailru | direct;
// url — документ транспорта или «host:port» для direct; key — общий секрет (для direct обязателен);
// codec — batched (по умолчанию) или legacy; maxToken/maxUid — для oneme.
func Start(transport, url, key, codec, maxToken, maxUid string, socksPort int) error {
	// Экономный режим очередей: у расширения VPN на iOS около 50 МБ памяти на всё.
	flux.SetLowMemory(true)
	listen := fmt.Sprintf("127.0.0.1:%d", socksPort)
	if msg := flux.StartProxy(transport, url, key, codec, maxToken, maxUid, listen, "", "", ""); msg != "" {
		return errors.New(msg)
	}
	return nil
}

// StartSession поднимает клиент в режиме сессии (0.7.11, ссылки openflux://v1/): несколько транспортов
// с приоритетами и автопереключением. specsJSON — {"context": "...", "transports": [{"type","url","priority"}]},
// для direct в url лежит адрес узла host:port (так хранит профиль ядро); key обязателен (не короче 16 знаков).
func StartSession(specsJSON, key string, socksPort int) error {
	flux.SetLowMemory(true)
	listen := fmt.Sprintf("127.0.0.1:%d", socksPort)
	if msg := flux.StartSessionProxy(specsJSON, key, listen, "", "", ""); msg != "" {
		return errors.New(msg)
	}
	return nil
}

// Stop останавливает клиент.
func Stop() { flux.StopProxy() }

// IsRunning — клиент запущен.
func IsRunning() bool { return flux.ProxyIsRunning() }

// Logs — накопленный журнал клиента (для экрана «Логи»).
func Logs() string { return flux.ReadLogs() }

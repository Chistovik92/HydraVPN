// Package hydraolc — клиент olcRTC для iOS (0.7.10).
//
// На iOS расширение VPN не может запускать подпроцессы, а две gomobile-библиотеки (Libbox и olcRTC)
// нельзя слинковать в одно расширение: у каждой свой рантайм Go. Поэтому этот пакет собирается
// одним `gomobile bind` вместе с libbox sing-box (ios/Bridge/build.sh) и запускает клиент olcRTC
// внутри того же процесса: локальный SOCKS5 на 127.0.0.1, к которому sing-box ходит мостом,
// как на Android и ПК. Пакет `mobile` апстрима не связывается напрямую — его имя совпадает с
// пакетом OpenFlux, а gomobile берёт префикс классов из имени пакета.
package hydraolc

import (
	"sync"

	olc "github.com/openlibrecommunity/olcrtc/mobile"
)

var (
	mu  sync.Mutex
	cur *olc.Runtime
)

// Start поднимает клиент и ждёт, пока он подключится к комнате (до readyMillis).
// provider: telemost | jitsi | wbstream; transport: datachannel | vp8channel | seichannel | videochannel;
// room — ID или ссылка комнаты; keyHex — 64 hex-символа; dns — «host:port» резолвера.
func Start(provider, transport, room, keyHex, dns string, socksPort, readyMillis int) error {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		_ = cur.Stop(3000)
		cur = nil
	}
	r := olc.New()
	steps := []func() error{
		func() error { return r.SetProvider(provider) },
		func() error { return r.SetTransport(transport) },
		func() error { return r.SetRoom(room) },
		func() error { return r.SetKey(keyHex) },
		func() error { return r.SetSocksListenHost("127.0.0.1") },
		func() error { return r.SetSocksPort(socksPort) },
	}
	if dns != "" {
		steps = append(steps, func() error { return r.SetDNS(dns) })
	}
	for _, step := range steps {
		if err := step(); err != nil {
			return err
		}
	}
	if err := r.Start(); err != nil {
		return err
	}
	if err := r.WaitReady(readyMillis); err != nil {
		_ = r.Stop(3000)
		return err
	}
	cur = r
	return nil
}

// Stop останавливает клиент (ничего не делает, если он не запущен).
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if cur != nil {
		_ = cur.Stop(3000)
		cur = nil
	}
}

// State — idle, starting, running, stopping или stopped.
func State() string {
	mu.Lock()
	defer mu.Unlock()
	if cur == nil {
		return "idle"
	}
	return cur.State()
}

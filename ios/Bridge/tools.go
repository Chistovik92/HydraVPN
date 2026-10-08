//go:build tools

// Пакеты, которые собирает `gomobile bind` (build.sh), но не импортирует код моста: libbox sing-box
// и обвязка gomobile. Эти строки держат их в go.mod/go.sum модуля.
package bridge

import (
	_ "github.com/sagernet/gomobile/bind"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)

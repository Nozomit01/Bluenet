// One app-owned process: Xray VLESS outbound plus a user-space IP stack.
// Configuration enters over stdin, never argv/files; a private Unix socket transfers the virtual TUN fd.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"golang.org/x/sys/unix"
)

type config struct {
	Xray   json.RawMessage `json:"xray"`
	Socket string          `json:"socket"`
	Proxy  string          `json:"proxy"`
}

func run() error {
	var cfg config
	if err := json.NewDecoder(io.LimitReader(os.Stdin, 131072)).Decode(&cfg); err != nil {
		return fmt.Errorf("config")
	}
	if len(cfg.Socket) == 0 || len(cfg.Socket) > 100 {
		return fmt.Errorf("socket")
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: cfg.Socket, Net: "unix"})
	if err != nil {
		return fmt.Errorf("listen")
	}
	defer listener.Close()
	defer os.Remove(cfg.Socket)
	if err = os.Chmod(cfg.Socket, 0600); err != nil {
		return fmt.Errorf("permissions")
	}
	listener.SetDeadline(time.Now().Add(15 * time.Second))
	conn, err := listener.AcceptUnix()
	if err != nil {
		return fmt.Errorf("accept")
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	data := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(4))
	_, n, _, _, err := conn.ReadMsgUnix(data, oob)
	if err != nil {
		return fmt.Errorf("descriptor")
	}
	msgs, err := unix.ParseSocketControlMessage(oob[:n])
	if err != nil || len(msgs) != 1 {
		return fmt.Errorf("descriptor")
	}
	fds, err := unix.ParseUnixRights(&msgs[0])
	if err != nil || len(fds) != 1 {
		return fmt.Errorf("descriptor")
	}
	fd := fds[0]
	instance, err := core.StartInstance("json", cfg.Xray)
	if err != nil {
		unix.Close(fd)
		return fmt.Errorf("xray_config")
	}
	defer instance.Close()
	engine.Insert(&engine.Key{MTU: 1500, Device: "fd://" + strconv.Itoa(fd), Proxy: cfg.Proxy, LogLevel: "silent", UDPTimeout: 60 * time.Second})
	if err = engine.Start(); err != nil {
		unix.Close(fd)
		return fmt.Errorf("netstack")
	}
	defer engine.Stop()
	if _, err = conn.Write([]byte("READY\n")); err != nil {
		return fmt.Errorf("ready")
	}
	conn.SetReadDeadline(time.Time{})
	// The service owns this control stream; EOF terminates all child sockets and threads.
	_, _ = io.Copy(io.Discard, conn)
	_ = os.Remove(cfg.Socket)
	// Parent loss must terminate promptly even if a remote flow is blocked.
	os.Exit(0)
	return nil
}
func main() {
	if err := run(); err != nil {
		os.Exit(1)
	}
}

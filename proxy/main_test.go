package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"github.com/xtls/xray-core/core"
	"golang.org/x/sys/unix"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestHelperProcess(t *testing.T) {
	if os.Getenv("BLUENET_PROXY_HELPER") != "1" {
		return
	}
	if run() != nil {
		os.Exit(1)
	}
	os.Exit(0)
}
func freePort(t *testing.T) int {
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func sum(data []byte) uint16 {
	var s uint32
	for len(data) > 1 {
		s += uint32(binary.BigEndian.Uint16(data))
		data = data[2:]
	}
	if len(data) > 0 {
		s += uint32(data[0]) << 8
	}
	for s > 65535 {
		s = (s & 65535) + (s >> 16)
	}
	return ^uint16(s)
}
func packet(seq, ack uint32, flags byte, payload []byte) []byte {
	p := make([]byte, 40+len(payload))
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	p[8] = 64
	p[9] = 6
	copy(p[12:16], []byte{26, 0, 0, 2})
	copy(p[16:20], []byte{192, 0, 2, 99})
	binary.BigEndian.PutUint16(p[20:22], 40000)
	binary.BigEndian.PutUint16(p[22:24], 80)
	binary.BigEndian.PutUint32(p[24:28], seq)
	binary.BigEndian.PutUint32(p[28:32], ack)
	p[32] = 0x50
	p[33] = flags
	binary.BigEndian.PutUint16(p[34:36], 65535)
	copy(p[40:], payload)
	pseudo := append(append([]byte{}, p[12:20]...), 0, 6, byte((len(p)-20)>>8), byte(len(p)-20))
	pseudo = append(pseudo, p[20:]...)
	binary.BigEndian.PutUint16(p[36:38], sum(pseudo))
	binary.BigEndian.PutUint16(p[10:12], sum(p[:20]))
	return p
}
func TestVirtualTunThroughVlessToLoopbackEcho(t *testing.T) {
	echo, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	go func() {
		for {
			c, e := echo.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	udpEcho, e := net.ListenPacket("udp", echo.Addr().String())
	if e != nil {
		t.Fatal(e)
	}
	defer udpEcho.Close()
	go func() {
		b := make([]byte, 1500)
		for {
			n, a, e := udpEcho.ReadFrom(b)
			if e != nil {
				return
			}
			udpEcho.WriteTo(b[:n], a)
		}
	}()
	id := "00000000-0000-4000-8000-000000000001"
	vp := freePort(t)
	sp := freePort(t)
	serverConfig := fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"decryption":"none","clients":[{"id":"%s"}]}}],"outbounds":[{"protocol":"freedom","settings":{"redirect":"%s","finalRules":[{"action":"allow","network":"tcp,udp","ip":["127.0.0.1/32"]}]}}]}`, vp, id, echo.Addr())
	server, e := core.StartInstance("json", []byte(serverConfig))
	if e != nil {
		t.Fatal(e)
	}
	defer server.Close()
	dir, e := os.MkdirTemp("", "bnproxy")
	if e != nil {
		t.Fatal(e)
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "s")
	xray := json.RawMessage(fmt.Sprintf(`{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"password","udp":true,"accounts":[{"user":"test","pass":"synthetic"}]}}],"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":%d,"users":[{"id":"%s","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}`, sp, vp, id))
	cfg, _ := json.Marshal(config{Xray: xray, Socket: path, Proxy: fmt.Sprintf("socks5://test:synthetic@127.0.0.1:%d", sp)})
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=TestHelperProcess")
	cmd.Env = append(os.Environ(), "BLUENET_PROXY_HELPER=1")
	cmd.Stdin = bytes.NewReader(cfg)
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	if e = cmd.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	until := time.Now().Add(10 * time.Second)
	for {
		if _, e = os.Stat(path); e == nil {
			break
		}
		if time.Now().After(until) {
			t.Fatalf("control socket unavailable: %s", output.String())
		}
		time.Sleep(10 * time.Millisecond)
	}
	conn, e := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	pair, e := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if e != nil {
		t.Fatal(e)
	}
	defer unix.Close(pair[0])
	defer unix.Close(pair[1])
	if _, _, e = conn.WriteMsgUnix([]byte{1}, unix.UnixRights(pair[1]), nil); e != nil {
		t.Fatal(e)
	}
	ready := make([]byte, 6)
	if _, e = io.ReadFull(conn, ready); e != nil || string(ready) != "READY\n" {
		t.Fatalf("not ready: %v %q %s", e, ready, output.String())
	}
	unix.SetsockoptTimeval(pair[0], unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Sec: 5})
	unix.Write(pair[0], packet(1000, 0, 2, nil))
	buf := make([]byte, 1500)
	n, e := unix.Read(pair[0], buf)
	if e != nil {
		t.Fatal(e)
	}
	if n < 40 || buf[33]&0x12 != 0x12 {
		t.Fatalf("no SYNACK: %x", buf[:n])
	}
	seq := binary.BigEndian.Uint32(buf[24:28]) + 1
	unix.Write(pair[0], packet(1001, seq, 16, nil))
	payload := []byte("combined-vless-self-test")
	unix.Write(pair[0], packet(1001, seq, 24, payload))
	received := false
	for i := 0; i < 20; i++ {
		n, e = unix.Read(pair[0], buf)
		if e != nil {
			t.Fatalf("receive: %v logs: %s", e, output.String())
		}
		t.Logf("frame flags=%x seq=%d ack=%d size=%d", buf[33], binary.BigEndian.Uint32(buf[24:28]), binary.BigEndian.Uint32(buf[28:32]), n)
		h := int(buf[0]&15) * 4
		offset := h + int(buf[h+12]>>4)*4
		if n > offset {
			if !bytes.Equal(buf[offset:n], payload) {
				t.Fatalf("echo mismatch: %q", buf[offset:n])
			}
			received = true
			break
		}
	}
	if !received {
		t.Fatal("TCP echo not received")
	}
	t.Log("TCP packet traversed netstack, SOCKS and VLESS")
	payload = []byte("udp-dns-path-self-test")
	udp := make([]byte, 28+len(payload))
	udp[0] = 0x45
	binary.BigEndian.PutUint16(udp[2:4], uint16(len(udp)))
	udp[8] = 64
	udp[9] = 17
	copy(udp[12:16], []byte{26, 0, 0, 2})
	copy(udp[16:20], []byte{192, 0, 2, 99})
	binary.BigEndian.PutUint16(udp[20:22], 40001)
	binary.BigEndian.PutUint16(udp[22:24], uint16(53))
	binary.BigEndian.PutUint16(udp[24:26], uint16(len(udp)-20))
	copy(udp[28:], payload)
	binary.BigEndian.PutUint16(udp[10:12], sum(udp[:20]))
	unix.Write(pair[0], udp)
	for i := 0; i < 20; i++ {
		n, e = unix.Read(pair[0], buf)
		if e != nil {
			t.Fatalf("UDP read failed: %v logs: %s", e, output.String())
		}
		if n < 28 || buf[9] != 17 {
			continue
		}
		h := int(buf[0]&15) * 4
		if bytes.Equal(buf[h+8:n], payload) {
			t.Log("UDP packet traversed netstack, SOCKS and VLESS")
			return
		}
	}
	t.Fatal("UDP echo not received")
}

package core

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"github.com/charliecharlieO-o/ridedaemon-go/hud/stream"
	"io"
	"net"
	"testing"
	"time"
)

// Fake HU exercises real TCP reverse ports and all three channels, without Android/hardware.
func TestStandaloneReverseHandshakeAndVideo(t *testing.T) {
	live := stream.NewContinuousSource(4, 12*time.Millisecond)
	hud := NewCfmotoHUD(20, &stream.MuxSource{Live: live})
	hud.SetHost(&EcHost{Ip: "127.0.0.1", Port: "10930", Package: "com.cfmoto"})
	hud.SetLocalAddress("127.0.0.1")
	client, hu := net.Pipe()
	defer hu.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	started := make(chan error, 1)
	go func() { started <- hud.StartStreamWithInitConn(ctx, client) }()
	header := make([]byte, 16)
	hu.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(hu, header); err != nil {
		t.Fatal(err)
	}
	body := make([]byte, int(binary.LittleEndian.Uint16(header[4:6]))-16)
	io.ReadFull(hu, body)
	var request map[string]string
	if err := json.Unmarshal(body, &request); err != nil {
		t.Fatal(err)
	}
	if request["packageName"] != "com.cfmoto" {
		t.Fatalf("EC package: %s", body)
	}
	// Servers must be listening before the EC init acknowledgement.
	pxc, err := net.DialTimeout("tcp", "127.0.0.1:10922", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer pxc.Close()
	ctrl, err := net.DialTimeout("tcp", "127.0.0.1:10921", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer ctrl.Close()
	video, err := net.DialTimeout("tcp", "127.0.0.1:10920", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer video.Close()
	for _, c := range []net.Conn{pxc, ctrl, video} {
		c.SetDeadline(time.Now().Add(3 * time.Second))
	}
	packet := make([]byte, 19)
	binary.LittleEndian.PutUint32(packet, 0x70000011)
	binary.LittleEndian.PutUint32(packet[4:], 19)
	binary.LittleEndian.PutUint32(packet[8:], 19^0x70000010)
	copy(packet[16:], "{}\n")
	hu.Write(packet)
	h := make([]byte, 16)
	binary.LittleEndian.PutUint32(h, 0x70000000)
	binary.LittleEndian.PutUint32(h[4:], 16)
	binary.LittleEndian.PutUint32(h[8:], 16^0x70000000)
	pxc.Write(h)
	io.ReadFull(pxc, h)
	if binary.LittleEndian.Uint32(h) != 0x70000001 {
		t.Fatalf("heartbeat response %x", h)
	}
	if err := <-started; err != nil {
		t.Fatal(err)
	}
	defer hud.StopStream(context.Background())
	// Capture request + video start, then one polled H.264 access unit.
	payload := make([]byte, 12)
	binary.LittleEndian.PutUint16(payload, 800)
	binary.LittleEndian.PutUint16(payload[2:], 384)
	binary.LittleEndian.PutUint32(payload[8:], 2)
	ch := make([]byte, 8)
	binary.LittleEndian.PutUint16(ch, 16)
	binary.LittleEndian.PutUint16(ch[2:], 12)
	ctrl.Write(append(ch, payload...))
	ack := make([]byte, 17)
	if _, err := io.ReadFull(ctrl, ack); err != nil {
		t.Fatal(err)
	}
	if binary.LittleEndian.Uint16(ack) != 17 || binary.LittleEndian.Uint16(ack[12:]) != 800 {
		t.Fatalf("capture ACK %x", ack)
	}
	ch = make([]byte, 8)
	binary.LittleEndian.PutUint16(ch, 112)
	ctrl.Write(ch)
	io.ReadFull(ctrl, ch)
	live.PushFrame([]byte{0, 0, 0, 1, 0x65, 1})
	video.Write([]byte{0x72, 0, 0, 0, 0, 0, 0, 0})
	frame := make([]byte, 14)
	if _, err := io.ReadFull(video, frame); err != nil {
		t.Fatal(err)
	}
	if frame[12] != 0x65 {
		t.Fatalf("frame %x", frame)
	}
	live.PushFrame([]byte{0, 0, 0, 1, 0x41, 2})
	video.Write([]byte{0x72, 0, 0, 0, 0, 0, 0, 0})
	if _, err := io.ReadFull(video, frame); err != nil {
		t.Fatal(err)
	}
	if frame[12] != 0x41 {
		t.Fatalf("predictive frame dropped: %x", frame)
	}

}

func TestStopDuringPendingECReadReleasesServers(t *testing.T) {
	live := stream.NewLiveStreamSource(20, time.Second, 3)
	hud := NewCfmotoHUD(20, &stream.MuxSource{Live: live})
	hud.SetHost(&EcHost{Ip: "127.0.0.1", Port: "10930", Package: "com.cfmoto"})
	hud.SetLocalAddress("127.0.0.1")
	client, hu := net.Pipe()
	defer hu.Close()
	started := make(chan error, 1)
	go func() { started <- hud.StartStreamWithInitConn(context.Background(), client) }()
	header := make([]byte, 16)
	hu.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(hu, header); err != nil {
		t.Fatal(err)
	}
	io.CopyN(io.Discard, hu, int64(binary.LittleEndian.Uint16(header[4:6]))-16)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := hud.StopStream(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-started:
		if err == nil {
			t.Fatal("cancelled init succeeded")
		}
	case <-ctx.Done():
		t.Fatal("start blocked after stop")
	}
	for _, port := range []string{"10922", "10921", "10920"} {
		ln, err := net.Listen("tcp", "127.0.0.1:"+port)
		if err != nil {
			t.Fatalf("port not released: %s: %v", port, err)
		}
		ln.Close()
	}
}

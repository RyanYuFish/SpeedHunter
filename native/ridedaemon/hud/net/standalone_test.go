package net

import (
	"bufio"
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func TestInitRejectIsNotHiddenBySuccessfulClose(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()
	go func() {
		h := make([]byte, 16)
		if _, err := io.ReadFull(server, h); err != nil {
			return
		}
		io.CopyN(io.Discard, server, int64(binary.LittleEndian.Uint16(h[4:6]))-16)
		server.Write((&ECService{}).encodePayload(&ECResponse{Code: 99, Separator: 0x70, Body: []byte("{}\n")}))
	}()
	if err := NewECService("unused", "10930", "com.cfmoto", "Android").InitStreamCmdWithConn(client); err == nil {
		t.Fatal("HU rejection silently accepted")
	}
}

type fixedFrames struct{}

func (fixedFrames) NextFrame(time.Time) ([]byte, error) { return []byte{0, 0, 0, 1, 0x65, 1}, nil }

func TestMediaPollDeliversFramedAnnexBAndReportsSent(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	s := NewMediaStream(":0", fixedFrames{}, 0, 0)
	sent := make(chan uint32, 1)
	s.OnFrameSent = func(n uint32) { sent <- n }
	s.wg.Add(1)
	go s.handleConn(server)
	client.SetDeadline(time.Now().Add(time.Second))
	if _, err := client.Write([]byte{0x72, 0, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, 14)
	if _, err := io.ReadFull(client, packet); err != nil {
		t.Fatal(err)
	}
	if binary.LittleEndian.Uint32(packet[:4]) != 10 || binary.LittleEndian.Uint32(packet[4:8]) != 0 || packet[12] != 0x65 {
		t.Fatalf("bad video packet %x", packet)
	}
	select {
	case n := <-sent:
		if n != 1 {
			t.Fatal(n)
		}
	case <-time.After(time.Second):
		t.Fatal("no sent event")
	}
	client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := s.Stop(ctx); err != nil {
		t.Fatal(err)
	}
}

// Compare application-added pacing, not Wi-Fi/phone/HU performance.
func BenchmarkLegacyPaced100KiB(b *testing.B) {
	for n := 0; n < b.N; n++ {
		w := bufio.NewWriter(io.Discard)
		if err := sendChunked(w, make([]byte, 100*1024), 4096, 3*time.Millisecond); err != nil {
			b.Fatal(err)
		}
	}
}
func BenchmarkContinuous100KiB(b *testing.B) {
	for n := 0; n < b.N; n++ {
		w := bufio.NewWriter(io.Discard)
		if err := sendChunked(w, make([]byte, 100*1024), 64*1024, 0); err != nil {
			b.Fatal(err)
		}
	}
}

type firstFrameOnly struct{ used bool }

func (s *firstFrameOnly) NextFrame(time.Time) ([]byte, error) {
	if s.used {
		return nil, nil
	}
	s.used = true
	return []byte{0, 0, 0, 1, 0x65, 1}, nil
}

func TestIdleReplyRefreshesExpiredPreviousFrameDeadline(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	s := NewMediaStream(":0", &firstFrameOnly{}, 0, 0)
	s.wg.Add(1)
	go s.handleConn(server)
	client.SetDeadline(time.Now().Add(time.Second))
	poll := []byte{0x72, 0, 0, 0, 0, 0, 0, 0}
	if _, err := client.Write(poll); err != nil {
		t.Fatal(err)
	}
	packet := make([]byte, 14)
	if _, err := io.ReadFull(client, packet); err != nil {
		t.Fatal(err)
	}
	for n := 0; n < 3; n++ {
		// Deterministically emulate the old absolute deadline expiring, without sleeping.
		server.SetWriteDeadline(time.Now().Add(-time.Second))
		if _, err := client.Write(poll); err != nil {
			t.Fatal(err)
		}
		idle := make([]byte, 4)
		if _, err := io.ReadFull(client, idle); err != nil {
			t.Fatalf("healthy idle poll %d timed out: %v", n, err)
		}
		if binary.LittleEndian.Uint32(idle) != 0 {
			t.Fatal("not idle reply")
		}
	}
	client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := s.Stop(ctx); err != nil {
		t.Fatal(err)
	}
}

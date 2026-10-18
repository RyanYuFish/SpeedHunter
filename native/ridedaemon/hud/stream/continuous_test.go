package stream

import (
	"bytes"
	"testing"
	"time"
)

var idr = []byte{0, 0, 0, 1, 0x65, 1}
var predicted = []byte{0, 0, 0, 1, 0x41, 2}

func take(t *testing.T, s *ContinuousSource, want []byte) {
	t.Helper()
	got, err := s.NextFrame(time.Now())
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("got %x,%v want %x", got, err, want)
	}
}
func TestContinuousPreservesDecodeOrderWithoutRepetition(t *testing.T) {
	s := NewContinuousSource(4, 0)
	s.PushFrame(predicted)
	take(t, s, nil)
	s.PushFrame(idr)
	s.PushFrame(predicted)
	take(t, s, idr)
	take(t, s, predicted)
	take(t, s, nil)
}
func TestContinuousOverflowRequiresFreshIDR(t *testing.T) {
	s := NewContinuousSource(2, 0)
	s.PushFrame(idr)
	s.PushFrame(predicted)
	s.PushFrame(predicted)
	if !s.NeedsKeyframe() {
		t.Fatal("missing sync request")
	}
	take(t, s, nil)
	s.PushFrame(predicted)
	take(t, s, nil)
	s.PushFrame(idr)
	s.PushFrame(predicted)
	take(t, s, idr)
	take(t, s, predicted)
	if s.Metrics().Drops != 4 {
		t.Fatal(s.Metrics())
	}
}
func TestContinuousWaitIsWokenByNewFrame(t *testing.T) {
	s := NewContinuousSource(4, time.Second)
	done := make(chan []byte, 1)
	go func() { a, _ := s.NextFrame(time.Now()); done <- a }()
	s.PushFrame(idr)
	select {
	case a := <-done:
		if !bytes.Equal(a, idr) {
			t.Fatal(a)
		}
	case <-time.After(time.Second):
		t.Fatal("lost frame wakeup")
	}
}
func TestContinuousPrepareInvalidatesPreviousChain(t *testing.T) {
	s := NewContinuousSource(4, 0)
	s.PushFrame(idr)
	s.PushFrame(predicted)
	s.PrepareForConsumer()
	s.PushFrame(predicted)
	take(t, s, nil)
	s.PushFrame(idr)
	take(t, s, idr)
}
func TestContinuousTwentyFramesDeliveredOnce(t *testing.T) {
	s := NewContinuousSource(4, 0)
	for n := 0; n < 20; n++ {
		a := predicted
		if n == 0 {
			a = idr
		}
		s.PushFrame(a)
		take(t, s, a)
	}
	take(t, s, nil)
	m := s.Metrics()
	if m.Inputs != 20 || m.Drops != 0 || m.Depth != 0 {
		t.Fatal(m)
	}
}
func TestContinuousEmptyWaitIsBounded(t *testing.T) {
	s := NewContinuousSource(4, time.Millisecond)
	take(t, s, nil)
}

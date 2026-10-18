package stream

import (
	"sync"
	"time"
)

type queuedAU struct {
	data []byte
	at   time.Time
}
type QueueMetrics struct {
	Inputs, Drops uint64
	Depth         int
	AgeMS         float64
}

// ContinuousSource retains H.264 decode order. Overflow invalidates the entire
// prediction chain, then waits for a fresh IDR rather than sending broken P frames.
// No frame is repeated; pacing is supplied by MediaCodec and the HU poll loop.
type ContinuousSource struct {
	mu            sync.Mutex
	queue         []queuedAU
	capacity      int
	wait          time.Duration
	notify        chan struct{}
	awaitIDR      bool
	active        bool
	inputs, drops uint64
}

func NewContinuousSource(capacity int, wait time.Duration) *ContinuousSource {
	if capacity < 1 {
		capacity = 1
	}
	return &ContinuousSource{capacity: capacity, wait: wait, notify: make(chan struct{}, 1), awaitIDR: true}
}
func (s *ContinuousSource) PushFrame(au []byte) {
	if len(au) == 0 {
		return
	}
	key := hasAnnexBNALType(au, 5)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inputs++
	s.active = true
	if len(s.queue) >= s.capacity {
		s.drops += uint64(len(s.queue))
		s.queue = nil
		s.awaitIDR = true
	}
	if s.awaitIDR && !key {
		s.drops++
		return
	}
	if key {
		s.awaitIDR = false
	}
	s.queue = append(s.queue, queuedAU{append([]byte(nil), au...), time.Now()})
	select {
	case s.notify <- struct{}{}:
	default:
	}
}
func (s *ContinuousSource) pop() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.queue) == 0 {
		return nil
	}
	au := s.queue[0]
	s.queue[0] = queuedAU{}
	s.queue = s.queue[1:]
	return au.data
}
func (s *ContinuousSource) NextFrame(time.Time) ([]byte, error) {
	if data := s.pop(); data != nil {
		return data, nil
	}
	if s.wait <= 0 {
		return nil, nil
	}
	// Drain a stale notification, then check again before waiting to avoid a lost wakeup.
	select {
	case <-s.notify:
	default:
	}
	if data := s.pop(); data != nil {
		return data, nil
	}
	timer := time.NewTimer(s.wait)
	defer timer.Stop()
	select {
	case <-s.notify:
		return s.pop(), nil
	case <-timer.C:
		return nil, nil
	}
}
func (s *ContinuousSource) IsActive(time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.active
}
func (s *ContinuousSource) PrepareForConsumer() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.drops += uint64(len(s.queue))
	s.queue = nil
	s.awaitIDR = true
}
func (s *ContinuousSource) NeedsKeyframe() bool { s.mu.Lock(); defer s.mu.Unlock(); return s.awaitIDR }
func (s *ContinuousSource) Metrics() QueueMetrics {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := QueueMetrics{Inputs: s.inputs, Drops: s.drops, Depth: len(s.queue)}
	if len(s.queue) > 0 {
		m.AgeMS = float64(time.Since(s.queue[0].at).Microseconds()) / 1000
	}
	return m
}

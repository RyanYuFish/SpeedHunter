package api

import (
	"testing"
	"time"
)

func TestBuildAnnexBAUFromAVCCRejectsTruncatedNAL(t *testing.T) {
	_, err := BuildAnnexBAUFromAVCC([]byte{0, 0, 0, 4, 0x65})
	if err == nil {
		t.Fatal("BuildAnnexBAUFromAVCC() accepted a truncated NAL")
	}
}

func TestNewMobileSessionAllowsLiveOnlyMode(t *testing.T) {
	config := NewMobileConfig(nil, 30, 10, 5, 10, 3)
	session, err := NewMobileSession(config, nil)
	if err != nil {
		t.Fatalf("create live-only mobile session: %v", err)
	}
	defer session.StopSession()
	if session.mux.NoSignal != nil {
		t.Fatal("live-only session unexpectedly has a static fallback")
	}
}

func TestResetVideoStreamDropsRotationPredictionChain(t *testing.T) {
	cfg := NewMobileConfig(nil, 15, 5, 5, 3, 1)
	cfg.ContinuousVideo = true
	ms, err := NewMobileSession(cfg, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ms.StopSession()
	ms.mux.Live.PushFrame([]byte{0, 0, 0, 1, 0x65, 1})
	ms.mux.Live.PushFrame([]byte{0, 0, 0, 1, 0x41, 2})
	ms.ResetVideoStream()
	if frame, _ := ms.mux.Live.NextFrame(time.Now()); len(frame) > 0 {
		t.Fatal("old orientation frames retained")
	}
	ms.mux.Live.PushFrame([]byte{0, 0, 0, 1, 0x41, 2})
	if frame, _ := ms.mux.Live.NextFrame(time.Now()); len(frame) > 0 {
		t.Fatal("prediction frame accepted without new IDR")
	}
	ms.mux.Live.PushFrame([]byte{0, 0, 0, 1, 0x65, 3})
	if frame, _ := ms.mux.Live.NextFrame(time.Now()); len(frame) == 0 {
		t.Fatal("fresh orientation IDR not accepted")
	}
}

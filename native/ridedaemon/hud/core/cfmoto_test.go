package core

import (
	"context"
	"testing"
)

func TestStopBeforeStartSignalsDone(t *testing.T) {
	hud := NewCfmotoHUD(30, nil)
	if err := hud.StopStream(context.Background()); err != nil {
		t.Fatalf("StopStream() error = %v", err)
	}
	select {
	case <-hud.Done():
	default:
		t.Fatal("StopStream() did not signal Done before stream start")
	}
}

func TestSetHostWhenRunningReturnsErrorWithoutPanicking(t *testing.T) {
	hud := NewCfmotoHUD(30, nil)
	hud.running = true
	if err := hud.SetHost(&EcHost{}); err == nil {
		t.Fatal("SetHost() succeeded while the HUD was running")
	}
}

func TestZeehoPackageAndLocalAddress(t *testing.T) {
	hud := NewCfmotoHUD(20, nil)
	if err := hud.SetHost(&EcHost{Ip: "192.168.0.1", Port: "10930", Package: "com.cfmoto"}); err != nil {
		t.Fatal(err)
	}
	if hud.phoneConfig.Package != "com.cfmoto" || hud.packageName != "com.cfmoto" {
		t.Fatal("wrong ZEEHO PXC package")
	}
	if err := hud.SetLocalAddress("not-an-ip"); err == nil {
		t.Fatal("invalid address accepted")
	}
	if err := hud.SetLocalAddress("192.168.0.2"); err != nil {
		t.Fatal(err)
	}
	hud.ConfigurePhone("test", "phone", "34")
	if hud.phoneConfig.PhoneModel != "phone" {
		t.Fatal("phone metadata not set")
	}
}

func TestStoppedSessionCannotRestartServers(t *testing.T) {
	hud := NewCfmotoHUD(20, nil)
	hud.StopStream(context.Background())
	if err := hud.StartStream(context.Background()); err == nil {
		t.Fatal("stopped session restarted")
	}
}

package patcher

import (
	"context"
	"strings"
	"sync"
	"testing"
)

func TestExecutePatch_PhasesParallelStdoutStderrAndRebootCheck(t *testing.T) {
	var frames []StreamFrame
	var mu sync.Mutex
	emit := func(f StreamFrame) {
		mu.Lock()
		defer mu.Unlock()
		frames = append(frames, f)
	}

	runner := func(ctx context.Context, cmd string, onLine func(stream, line string)) (int, error) {
		if strings.Contains(cmd, "apt-get update") {
			onLine("STDOUT", "Hit:1 bookworm InRelease")
			return 0, nil
		}
		if strings.Contains(cmd, "apt-get -y upgrade") {
			// Emit large interleaved stdout and stderr concurrently to prove thread safety
			var wg sync.WaitGroup
			wg.Add(2)
			go func() {
				defer wg.Done()
				for i := 0; i < 100; i++ {
					onLine("STDOUT", "Unpacking package line")
				}
			}()
			go func() {
				defer wg.Done()
				for i := 0; i < 100; i++ {
					onLine("STDERR", "debconf warning line")
				}
			}()
			wg.Wait()
			return 0, nil
		}
		if strings.Contains(cmd, "reboot-required") {
			return 0, nil // 0 -> reboot required
		}
		return 0, nil
	}

	refresh := "apt-get update -qq"
	reboot := "test -f /var/run/reboot-required"
	Execute(
		context.Background(),
		runner,
		ExecConfig{
			Mode: "FULL_UPGRADE",
			Spec: ExecSpec{
				RefreshCommand:          &refresh,
				FullUpgradeCommand:      "apt-get -y upgrade",
				RebootCheckCommand:      &reboot,
				RebootRequiredExitCodes: []int{0},
				TimeoutSeconds:          60,
				IdleTimeoutSeconds:      30,
			},
		},
		emit,
	)

	if len(frames) < 204 {
		t.Fatalf("expected at least 204 frames, got %d", len(frames))
	}

	// Verify strictly monotonic sequence numbers
	for i, f := range frames {
		wantSeq := int64(i + 1)
		if f.Seq != wantSeq {
			t.Fatalf("frame[%d].Seq = %d, want %d", i, f.Seq, wantSeq)
		}
	}

	last := frames[len(frames)-1]
	if last.Type != "RESULT" || last.Status != "SUCCEEDED" {
		t.Fatalf("unexpected terminal frame: %+v", last)
	}
	if last.RebootRequired == nil || !*last.RebootRequired {
		t.Fatalf("expected rebootRequired=true, got %+v", last.RebootRequired)
	}
}

func TestExecutePatch_ConnectionLostAndCancellation(t *testing.T) {
	t.Run("connection_lost_without_exit_code", func(t *testing.T) {
		var last StreamFrame
		Execute(
			context.Background(),
			func(ctx context.Context, cmd string, onLine func(stream, line string)) (int, error) {
				onLine("STDOUT", "Restarting sshd...")
				return -1, ErrConnectionLost
			},
			ExecConfig{
				Mode: "FULL_UPGRADE",
				Spec: ExecSpec{
					FullUpgradeCommand: "apt-get -y upgrade",
					TimeoutSeconds:     60,
					IdleTimeoutSeconds: 30,
				},
			},
			func(f StreamFrame) { last = f },
		)
		if last.Type != "RESULT" || last.Status != "CONNECTION_LOST" || *last.ErrorCode != "CONNECTION_LOST" {
			t.Fatalf("expected CONNECTION_LOST result frame, got %+v", last)
		}
	})

	t.Run("context_cancellation_emits_cancelled_result", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		var last StreamFrame
		Execute(
			ctx,
			func(rCtx context.Context, cmd string, onLine func(stream, line string)) (int, error) {
				cancel()
				<-rCtx.Done()
				return -1, rCtx.Err()
			},
			ExecConfig{
				Mode: "FULL_UPGRADE",
				Spec: ExecSpec{
					FullUpgradeCommand: "apt-get -y upgrade",
					TimeoutSeconds:     60,
					IdleTimeoutSeconds: 30,
				},
			},
			func(f StreamFrame) { last = f },
		)
		if last.Type != "RESULT" || last.Status != "CANCELLED" || *last.ErrorCode != "CANCELLED" {
			t.Fatalf("expected CANCELLED result frame, got %+v", last)
		}
	})
}

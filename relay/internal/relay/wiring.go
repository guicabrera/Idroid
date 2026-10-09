package relay

import (
	"context"
	"log"
	"time"
)

// NewStores constructs the device registry, item store, websocket hub, and
// pairing session store backing a Server, loading any existing state from
// cfg.DataDir. The pairing store is intentionally in-memory only (sessions
// are short-lived; see pairing.go), so it has nothing to load from disk.
func NewStores(cfg Config) (*deviceStore, *itemStore, *hub, *pairingStore, error) {
	devices, err := newDeviceStore(cfg.DataDir)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	items, err := newItemStore(cfg.DataDir)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	return devices, items, newHub(), newPairingStore(), nil
}

// StartCleanupLoop periodically deletes items past their retention window.
// It stops when ctx is canceled; the returned func blocks until the
// background goroutine has exited.
func StartCleanupLoop(ctx context.Context, items *itemStore, interval time.Duration) func() {
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				expired := items.CleanupExpired(time.Now().UTC())
				if len(expired) > 0 {
					log.Printf("cleanup: expired %d item(s)", len(expired))
				}
			}
		}
	}()
	return func() { <-done }
}

// Command idroid-relay is the Mac mini store-and-forward relay described in
// ADR-0001 and ADR-0002: it accepts uploads (files, text/links, clipboard
// pushes) for a recipient device and holds them until that device picks
// them up, bounded by a retention window.
//
// It is meant to run on a machine already reachable only via a Tailscale
// tailnet; it does not implement its own transport encryption or
// internet-facing hardening. See relay/README.md.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"idroid-relay/internal/relay"
)

func main() {
	cfg, err := relay.LoadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	devices, items, h, pairing, err := relay.NewStores(cfg)
	if err != nil {
		log.Fatalf("init storage: %v", err)
	}

	srv := relay.NewServer(cfg, devices, items, h, pairing)
	httpServer := &http.Server{
		Addr:    cfg.ListenAddr,
		Handler: srv,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	stopCleanup := relay.StartCleanupLoop(ctx, items, time.Hour)
	defer stopCleanup()

	go func() {
		log.Printf("idroid-relay listening on %s (data dir: %s, retention: %s)", cfg.ListenAddr, cfg.DataDir, cfg.Retention)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("serve: %v", err)
		}
	}()

	<-ctx.Done()
	log.Println("shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		log.Printf("shutdown: %v", err)
	}
}

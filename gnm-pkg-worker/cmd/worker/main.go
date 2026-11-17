package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/skoczo/LanAlmanac/gnm-pkg-worker/internal/httpapi"
)

// workerVersion can be overridden at build time via:
// -ldflags="-X main.workerVersion=..."
var workerVersion = "0.1.0-dev"

func main() {
	addr := os.Getenv("GNM_PKG_WORKER_ADDR")
	if addr == "" {
		addr = ":8081"
	}

	secretBytes := []byte(os.Getenv("GNM_PKG_WORKER_AUTH_SECRET"))
	defer clear(secretBytes)

	handler, err := httpapi.NewHandler(httpapi.Config{
		AuthSecret:    secretBytes,
		WorkerVersion: workerVersion,
	})
	clear(secretBytes)
	if err != nil {
		log.Fatalf("Failed to initialize worker HTTP handler: %v", err)
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Printf("gnm-pkg-worker (%s, contract %s) listening on %s", workerVersion, httpapi.ContractVersion, addr)
		if listenErr := srv.ListenAndServe(); listenErr != nil && !errors.Is(listenErr, http.ErrServerClosed) {
			errCh <- listenErr
		}
		close(errCh)
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-errCh:
		if err != nil {
			log.Fatalf("Worker HTTP server error: %v", err)
		}
	case sig := <-sigCh:
		log.Printf("Received signal %s, shutting down gracefully...", sig)
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			log.Printf("Graceful shutdown error: %v", err)
		}
	}
}


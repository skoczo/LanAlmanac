# Stage 1: Build static Go binary
FROM golang:1.23-bookworm AS builder

ARG WORKER_VERSION=0.1.0-dev

WORKDIR /src
COPY go.mod ./
RUN go mod download

COPY cmd/ ./cmd/
COPY internal/ ./internal/

RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w -X main.workerVersion=${WORKER_VERSION}" \
    -o /out/pkg-worker \
    ./cmd/worker

# Stage 2: Optional test target (skipped during default BuildKit image build)
FROM builder AS test
RUN go vet ./... && go test -v -race -cover ./...

# Stage 3: Minimal distroless runtime (non-root, no shell)
FROM gcr.io/distroless/static-debian12:nonroot

USER nonroot:nonroot
COPY --from=builder /out/pkg-worker /pkg-worker

EXPOSE 8081
ENTRYPOINT ["/pkg-worker"]

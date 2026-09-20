#!/bin/bash

# Setup logging
LOG_FILE="test_execution_$(date +%Y%m%d_%H%M%S).log"
echo "Logging output to $LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "======================================"
echo " Starting GreatNetworkManager E2E Tests"
echo "======================================"
echo ""

# 1. Run Backend Tests
echo "--- [1/2] Running Backend Tests (Java / Testcontainers) ---"
echo "Ensuring Docker daemon is running..."
if ! docker info > /dev/null 2>&1; then
  echo "Error: Docker does not appear to be running. Please start Docker first."
  exit 1
fi
QUARKUS_PID=""
cleanup() {
  if [ -n "$QUARKUS_PID" ]; then
    echo "Stopping background Quarkus server (PID: $QUARKUS_PID)..."
    kill "$QUARKUS_PID" 2>/dev/null || true
  fi
  echo "Cleaning up Docker images built for tests..."
  docker rmi gnm-ne-linux-server:latest gnm-ne-router-sim:latest gnm-traffic-generator:latest 2>/dev/null || true
}
trap cleanup EXIT

echo "Building test environment container images..."
set -e
docker build -t gnm-ne-linux-server:latest -f Dockerfile.ne-linux-server .
docker build -t gnm-ne-router-sim:latest -f Dockerfile.ne-router-sim .
docker build -t gnm-traffic-generator:latest -f Dockerfile.traffic-generator .
set +e

echo "Cleaning up leftover vault state and compose containers..."
rm -f gnm-app/keys/.vault_master
docker compose -f docker-compose.e2e.yml down -v 2>/dev/null || true
docker network prune -f 2>/dev/null || true

./gradlew :gnm-app:test
BACKEND_STATUS=$?

if [ $BACKEND_STATUS -eq 0 ]; then
    echo "Backend tests completed successfully!"
else
    echo "Backend tests FAILED! (Continuing to frontend tests...)"
fi
echo ""

# 2. Run Frontend Tests
echo "--- [2/2] Running Frontend Tests (Playwright) ---"

echo "Starting Quarkus backend server for E2E tests..."
./gradlew :gnm-app:quarkusDev > /dev/null 2>&1 &
QUARKUS_PID=$!

echo "Waiting for backend server to become available on port 8080..."
for i in {1..60}; do
  if curl -s http://localhost:8080/api/version > /dev/null 2>&1; then
    echo "Backend server is UP & READY!"
    break
  fi
  sleep 1
done

cd frontend

echo "Installing frontend dependencies..."
npm install

echo "Ensuring Playwright browsers are installed..."
npx playwright install

echo "Running E2E tests..."
npm run test:e2e
FRONTEND_STATUS=$?

if [ -n "$QUARKUS_PID" ]; then
    kill "$QUARKUS_PID" 2>/dev/null || true
    QUARKUS_PID=""
fi

if [ $FRONTEND_STATUS -eq 0 ]; then
    echo "Frontend tests completed successfully!"
else
    echo "Frontend tests FAILED!"
fi
echo ""

echo "======================================"
echo " Test Summary: "
if [ $BACKEND_STATUS -eq 0 ] && [ $FRONTEND_STATUS -eq 0 ]; then
    echo " ALL TESTS PASSED SUCCESSFULLY! "
    exit 0
else
    echo " SOME TESTS FAILED."
    echo " Backend Status: $BACKEND_STATUS"
    echo " Frontend Status: $FRONTEND_STATUS"
    echo ""
    echo " Check $LOG_FILE for details."
    exit 1
fi
echo "======================================"

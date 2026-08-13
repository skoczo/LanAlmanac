#!/bin/bash

# Navigate to the environment directory
cd "$(dirname "$0")/docker-sim-env" || exit

if [ "$1" == "clean" ]; then
    echo "=========================================================="
    echo " Cleaning up Isolated Simulation Environment"
    echo "=========================================================="
    echo "Removing containers, networks, and all associated volumes..."
    docker-compose down -v --remove-orphans
    echo "Done!"
    exit 0
elif [ "$1" == "stop" ]; then
    echo "=========================================================="
    echo " Stopping Isolated Simulation Environment"
    echo "=========================================================="
    docker-compose down
    echo "Done!"
    exit 0
elif [ "$1" == "restart" ]; then
    echo "=========================================================="
    echo " Restarting Isolated Simulation Environment"
    echo "=========================================================="
    docker-compose down
    docker-compose up --build -d
    echo "Done!"
    exit 0
elif [ "$1" == "logs" ]; then
    docker-compose logs -f
    exit 0
fi

echo "=========================================================="
echo " Starting Isolated GreatNetworkManager Simulation Environment"
echo "=========================================================="
echo "Building all images (including GNM app from root source code) and starting containers..."
echo "This network is fully isolated (internal) but GNM will be exposed on port 8080."
echo "Please wait, as building the Quarkus app and frontend may take a few minutes..."

# Build and start all services in detached mode
docker-compose up --build -d

echo "=========================================================="
echo " Done! The environment is running."
echo ""
echo " -> Access the GNM App GUI at: http://localhost:8080"
echo " -> To view logs in real-time: ./start_sim.sh logs"
echo " -> To shut down the environment: ./start_sim.sh stop"
echo " -> To restart the environment: ./start_sim.sh restart"
echo " -> To completely destroy it (including DB data): ./start_sim.sh clean"
echo "=========================================================="

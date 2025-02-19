#!/bin/bash
set -e

TIMEOUT=600

declare -A CHECKS=(
    ["cassandra-db"]="status"
    ["zookeeper"]="status"
    ["kafka"]="status"
    ["sentiment-analysis"]="status"
    ["cassandra-init"]="exited"
    ["kafka-init"]="exited"
)

if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

start_services() {
    echo "Uruchamianie kontenerów..."
    docker-compose up -d
}

container_check() {
    local container=$1
    local check_type=${CHECKS[$container]}
    local status=$(docker inspect --format "{{json .State.Status}}" "$container" 2>/dev/null || echo "unknown")

    case $check_type in
        "health")
            [[ $status == "\"healthy\"" ]] && return 0 ;;
        "status")
            [[ $status == "\"running\"" ]] && return 0 ;;
        "exited")
            [[ $status == "\"exited\"" ]] && return 0 ;;
        *)
            return 1 ;;
    esac

    return 1
}

wait_for_containers() {
    local start_time=$(date +%s)

    for container in "${!CHECKS[@]}"; do
        echo -n "Czekam na $container "

        while true; do
            if container_check "$container"; then
                echo -e "\n$container gotowy!"
                break
            fi

            if [ $(($(date +%s) - start_time)) -ge $TIMEOUT ]; then
                echo -e "\nTimeout dla $container!"
                docker-compose -f "$DOCKER_COMPOSE_FILE" logs "$container"
                exit 1
            fi

            echo -n "."
            sleep 5
        done
    done
}

main() {
    start_services
    wait_for_containers

    echo -e "\nUruchamiam aplikację!"

    sbt clean assembly

    if [ -z "$JAVA_18" ]; then
        echo "JAVA_18 nie jest ustawiona. Użyję domyślnej wersji Java."
        java -jar target/scala-3.3.0/stream-scout-app-assembly-0.1.0-SNAPSHOT.jar
    else
        echo "Używam Javy 18 z lokalizacji: $JAVA_18"
        "$JAVA_18/bin/java" -jar target/scala-3.3.0/stream-scout-app-assembly-0.1.0-SNAPSHOT.jar
    fi

    read q
    docker-compose down
}

main

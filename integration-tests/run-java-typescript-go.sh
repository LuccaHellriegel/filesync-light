#!/bin/bash
function server() {
    docker run -d \
        --name sync-server-container \
        -p 8080:8080 \
        -e SERVER_PORT=8080 \
        -e SERVER_FOLDER=server-folder \
        -e API_KEY=SUPER-SECRET-API-KEY \
        -e CHUNK_SIZE=10000000 \
        -v $(pwd)/input-folders/server-folder:/app/server-folder \
        localhost/sync-server:latest
}

function stopServer() {
    docker rm -f sync-server-container || true
}

function logsServer() {
    docker logs sync-server-container
}

function client() {
    docker run -d \
        --name sync-client-$2-container$1 \
        --net=host \
        -e SERVER_PORT=8080 \
        -e SERVER_HOST=localhost \
        -e CLIENT_FOLDER=client-folder$1 \
        -e API_KEY=SUPER-SECRET-API-KEY \
        -e CHUNK_SIZE=10000000 \
        -v $(pwd)/input-folders/client-folder$1:/app/client-folder$1 \
        localhost/sync-client-$2:latest

}

function stopClient() {
    docker rm -f sync-client-$2-container$1 || true
}

function logsClient() {
    docker logs sync-client-$2-container$1
}

cp -r ./input-folders ./input-folders-original

echo "--- TEST START ---"
server

client 1 golang

sleep 1

client 2 typescript
client 3 golang

sleep 1

client 4 typescript

sleep 1

echo "--- TEST END ---"
echo "--- LOGS START ---"
logsServer
echo "------------------"
logsClient 1 golang
echo "------------------"
logsClient 2 typescript
echo "------------------"
logsClient 3 golang
echo "------------------"
logsClient 4 typescript
echo "--- LOGS END ---"

echo "--- SHUTDOWN START ---"
stopServer
stopClient 1
stopClient 2
stopClient 3
stopClient 4
echo "--- SHUTDOWN END ---"
echo "--- EVALUATION START ---"
diff -r ./expected-folders/ ./input-folders
echo "If there is no output here. The test was a success!"
echo "--- EVALUATION END ---"
echo "--- CLEANUP START ---"
rm -rf ./input-folders
mv ./input-folders-original ./input-folders
echo "--- CLEANUP END ---"

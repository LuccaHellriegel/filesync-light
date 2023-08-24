#!/bin/bash
docker run -d \
  --name sync-client-golang-container$CLIENT_NUMBER \
  --net=host \
  -e SERVER_PORT=8080 \
  -e SERVER_HOST=localhost \
  -e CLIENT_FOLDER=mounted-client-folder$CLIENT_NUMBER \
  -e API_KEY=SUPER-SECRET-API-KEY \
  -e CHUNK_SIZE=10000000 \
  -v $(pwd)/mounted-client-folder$CLIENT_NUMBER:/app/mounted-client-folder$CLIENT_NUMBER \
  localhost/sync-client-golang:latest

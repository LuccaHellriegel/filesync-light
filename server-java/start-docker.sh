docker run -d \
  --name sync-server-container \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e SERVER_FOLDER=mounted-server-folder \
  -e API_KEY=SUPER-SECRET-API-KEY \
  -e CHUNK_SIZE=10000000 \
  -v $(pwd)/mounted-server-folder:/app/mounted-server-folder \
  localhost/sync-server:latest
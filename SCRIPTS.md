All code and scripts have been tested with Ubuntu 22.04.2 LTS. Usage examples assume a Linux terminal.

# Scripts

Each codebase uses the idiomatic choice of build tool, but I created some scripts to make building and manual testing easier.

The scripts mostly need to be executed in the folder that they are in because the paths are relative.

All three codebases roughly have the same scripts:

- build.sh: Builds each client/server. See the individual READMEs for the requirements or simply use build-docker.sh
- start-debug.sh: Starts the freshly built client/server with the debug flag, outside of any container. This flag sets dummy env vars.
- start-help.sh: Starts the freshly built client/server with the help flag, outside of any container. This gives more info on the env vars.
- build-docker.sh: Builds the docker for each client/server.
- start-docker.sh: Starts teh docker for each client/server.
- stop-docker.sh: Removes the container for each client/server.
- logs-docker.sh: Follows the log-stream for each client/server.

## Client specific differences

There is one client specific script: create-client-folder.sh
This creates a "mounted-client-folder"-folder - optionally with a CLIENT_NUMBER env var.
I used this for creating local folders to mount into different instances of the clients, so I could test multiple instances in parallel.

Each start/stop/logs script in a client folder has this optional CLIENT_NUMBER env var.
For example if I would want to start a second client-docker, I could use:

```sh
CLIENT_NUMBER=2 ./create-client-folder.sh
CLIENT_NUMBER=2 ./start-docker.sh

```

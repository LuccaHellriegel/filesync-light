# What to expect

The directories of the clients are synced with each other through the server once they are connected. Currently, each client only shares their files on initial connection but receives all incoming files from other clients.

# How to run with docker

- execute each build-docker.sh script in each codebase
- depending on how many clients should be run (see SCRIPTS.md), create at least one "mounted-client-folder" in each client root folder to be able to mount it into the docker containers
- add some files into each "mounted-client-folder"
- run start-docker.sh for each codebase to instantiate 1 server and 2 clients
- verify that all files are synced between all clients and the server

Alternatively check out the README.md of the integration tests to see multiple clients in action.

# Further information

SCRIPTS.md: describes how to use the included scripts in detail.
DESIGN.pdf: describes the approach that I took for solving this problem and gives an overview over the architecture.

Each folder additionally has its own readme with further information for setup.

# Integration Tests

The scripts start a server and multiple clients with different timings. This leads too many different scenarios of concurrent read / write being exercised.

At the end of each test we execute a diff between the expected and actual folders. If nothing is found, the test was a success.

Only one test should be run at a time because the test changes the folder contents temporarily.

There are two tests for each server/client combination and then two tests for two examples of instantiation ordering of both clients used together.

## Requirements

The integration tests assume already build dockers for the client/server.
It assumes the docker images can be accessed via localhost/...

Linux utilities:

- cp / sleep / mv / rm
- diff

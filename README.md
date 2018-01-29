# allo, gator

Makes creating billing projects snappy

## Project Status 
This project is under active development. It is not yet ready for deployment.

## Building and Running GPAlloc
Clone and go into the repo:
```
$ git clone https://github.com/broadinstitute/gpalloc.git
$ cd gpalloc
```
Spin up MySQL locally (ensure Docker is running):
```
$ ./docker/run-mysql.sh start gpalloc
```
Build GPAlloc and run the tests:
```
export SBT_OPTS="-Xmx2G -Xms1G -Dmysql.host=localhost -Dmysql.port=3311"
sbt clean compile test
```
Once you're done, tear down MySQL:
```
./docker/run-mysql.sh stop gpalloc
```

#!/bin/bash

set -e

GPALLOC_DIR=$1
cd $GPALLOC_DIR
rm -f gpalloc*.jar

# Test
sbt -J-Xms4g -J-Xmx4g test -Dmysql.host=mysql -Dmysql.port=3306
sbt -J-Xms4g -J-Xmx4g assembly
GPALLOC_JAR=$(find target | grep 'gpalloc.*\.jar')
mv $GPALLOC_JAR .
sbt clean
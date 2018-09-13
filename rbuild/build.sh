#!/bin/bash

cd ../ &&
mvn package -DskipTests &&
find . -name '*.jar'  -exec cp '{}' ./rbuild/bin/lib \; &&
echo done.
cd -
